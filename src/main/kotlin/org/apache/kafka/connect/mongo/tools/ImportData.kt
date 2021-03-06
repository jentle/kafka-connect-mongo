package org.apache.kafka.connect.mongo.tools

import com.mongodb.MongoClient
import com.mongodb.MongoClientURI
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.connect.mongo.MongoSourceConfig
import org.bson.Document
import org.bson.types.ObjectId
import org.json.JSONObject
import org.quartz.*
import org.quartz.impl.StdSchedulerFactory
import org.slf4j.LoggerFactory
import java.io.FileInputStream
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

data class MessageData(val topic: String,
                       val key: JSONObject,
                       val value: JSONObject)

/**
 * @author Xu Jingxin
 * Import all the data from one collection into kafka
 * Then save the offset *
 * @param uri mongodb://[user:pwd@]host:port
 * @param dbs Database.collection strings, combined with comma
 * @param topicPrefix Producer topic in kafka
 * @param props Kafka producer props
 */
class ImportJob(val uri: String,
                val dbs: String,
                val topicPrefix: String,
                val props: Properties,
                val bulkSize: Int = 1000) {

    companion object {
        private val log = LoggerFactory.getLogger(ImportJob::class.java)
    }

    private val messages = ConcurrentLinkedQueue<MessageData>()
    private var producer: KafkaProducer<String, String> = KafkaProducer(props)

    /**
     * Start job
     */
    fun start() {
        log.info("Start import data from {}", dbs)
        val threadGroup = mutableListOf<Thread>()
        var threadCount = 0
        dbs.split(",").dropLastWhile(String::isEmpty).forEach {
            log.trace("Import database: {}", it)
            val importDB = ImportDB(uri, it, topicPrefix, messages, bulkSize)
            val t = Thread(importDB)
            threadCount += 1
            threadGroup.add(t)
            t.start()
        }

        while (true) {
            threadCount = threadGroup.filter(Thread::isAlive).count()
            if (threadCount == 0 && messages.isEmpty()) {
                break
            }
            flush()
            Thread.sleep(100)
        }

        producer.close()
        log.info("Import finish")
    }

    /**
     * Flush messages into kafka
     */
    fun flush() {
        while (!messages.isEmpty()) {
            val message = messages.poll()
            log.trace("Poll document {}", message)

            val record = ProducerRecord(
                    message.topic,
                    message.key.toString(),
                    message.value.toString()
            )
            log.trace("Record {}", record)
            producer.send(record)
        }
    }
}

/**
 * Import data from single collection
 * @param uri mongodb://[user:pwd@]host:port
 * @param dbName mydb.users
 */
class ImportDB(val uri: String,
               val dbName: String,
               val topicPrefix: String,
               var messages: ConcurrentLinkedQueue<MessageData>,
               val bulkSize: Int) : Runnable {

    private val mongoClient: MongoClient = MongoClient(MongoClientURI(uri))
    private val mongoDatabase: MongoDatabase
    private val mongoCollection: MongoCollection<Document>
    private var offsetId: ObjectId? = null
    private val snakeDb: String = dbName.replace("\\.".toRegex(), "_")
    private var offsetCount = 0
    private val maxMessageSize = 3000

    companion object {
        private val log = LoggerFactory.getLogger(ImportDB::class.java)
    }

    init {
        val (db, collection) = dbName.split("\\.".toRegex()).dropLastWhile(String::isEmpty)
        mongoDatabase = mongoClient.getDatabase(db)
        mongoCollection = mongoDatabase.getCollection(collection)

        log.trace("Start querying {}", dbName)
    }

    override fun run() {
        do {
            log.info("Read messages at $dbName from offset {}, count {}", offsetId, offsetCount)
            var iterator = mongoCollection.find()
            if (offsetId != null) {
                iterator = iterator.filter(Filters.gt("_id", offsetId))
            }
            iterator = iterator
                    .sort(Document("_id", 1))
                    .limit(bulkSize)
            try {
                for (document in iterator) {
                    messages.add(getResult(document))
                    offsetId = document["_id"] as ObjectId
                    offsetCount += 1
                }
                while (messages.size > maxMessageSize) {
                    log.warn("Message overwhelm! database {}, docs {}, messages {}",
                            dbName,
                            offsetCount,
                            messages.size)
                    Thread.sleep(500)
                }
            } catch (e: Exception) {
                log.error("Querying error: {}", e.message)
            }
        } while (iterator.count() > 0)
        try {
            mongoClient.close()
        } catch (e: Exception) {
            log.error("Close db client error: {}", e.message)
        }
        log.info("Task finish, database {}, count {}",
                dbName,
                offsetCount)
    }

    fun getResult(document: Document): MessageData {
        val id = document["_id"] as ObjectId
        val key = JSONObject(mapOf(
                "schema" to mapOf(
                        "type" to "string",
                        "optional" to true
                ), "payload" to id.toHexString()
        ))
        val topic = "${topicPrefix}_$snakeDb"
        val message = JSONObject(mapOf(
                "schema" to mapOf(
                        "type" to "struct",
                        "fields" to listOf(
                                mapOf(
                                        "type" to "int32",
                                        "optional" to true,
                                        "field" to "ts"
                                ),
                                mapOf(
                                        "type" to "int32",
                                        "optional" to true,
                                        "field" to "inc"
                                ),
                                mapOf(
                                        "type" to "string",
                                        "optional" to true,
                                        "field" to "id"
                                ),
                                mapOf(
                                        "type" to "string",
                                        "optional" to true,
                                        "field" to "database"
                                ),
                                mapOf(
                                        "type" to "string",
                                        "optional" to true,
                                        "field" to "op"
                                ),
                                mapOf(
                                        "type" to "string",
                                        "optional" to true,
                                        "field" to "object"
                                )
                        ),
                        "optional" to false,
                        "name" to topic
                ),
                "payload" to mapOf(
                        "id" to id.toHexString(),
                        "ts" to id.timestamp,
                        "inc" to 0,
                        "database" to snakeDb,
                        "op" to "i",
                        "object" to document.toJson()
                )))
        return MessageData(topic, key, message)
    }
}

class ScheduleJob : Job {
    override fun execute(context: JobExecutionContext) {
        val props = context.mergedJobDataMap["props"] as Properties
        val uri = context.mergedJobDataMap[MongoSourceConfig.MONGO_URI_CONFIG] as String
        val dbs = context.mergedJobDataMap[MongoSourceConfig.DATABASES_CONFIG] as String
        val topicPrefix = context.mergedJobDataMap[MongoSourceConfig.TOPIC_PREFIX_CONFIG] as String
        ImportJob(uri, dbs, topicPrefix, props).start()
    }
}

object JobConfig {
    val SCHEDULE = "schedule"
}

fun main(args: Array<String>) {
    if (args.isEmpty()) throw Exception("Missing config file path!")
    val log = LoggerFactory.getLogger(ImportJob::class.java)

    val configFilePath = args[0]
    val props = Properties()
    props.load(FileInputStream(configFilePath))

    val missingKey = arrayOf(
            MongoSourceConfig.MONGO_URI_CONFIG,
            MongoSourceConfig.DATABASES_CONFIG,
            MongoSourceConfig.TOPIC_PREFIX_CONFIG).find { props[it] == null }

    if (missingKey != null) throw Exception("Missing config property: $missingKey")

    val schedule = props[JobConfig.SCHEDULE] as String?
    if (schedule.isNullOrEmpty()) {
        log.info("Execute in single use mode")
        // Execute once
        ImportJob(
                props[MongoSourceConfig.MONGO_URI_CONFIG] as String,
                props[MongoSourceConfig.DATABASES_CONFIG] as String,
                props[MongoSourceConfig.TOPIC_PREFIX_CONFIG] as String,
                props)
                .start()
    } else {
        log.info("Execute in cron mode with schedule of {}", schedule)
        props["props"] = props
        // Execute in cron mode
        val scheduler = StdSchedulerFactory.getDefaultScheduler()
        scheduler.start()
        val job = JobBuilder.newJob(ScheduleJob::class.java)
                .setJobData(JobDataMap(props))
                .withIdentity("job_mongo_import", "group1")
                .build()
        val trigger = TriggerBuilder.newTrigger()
                .withIdentity("trigger_mongo_import", "group1")
                .withSchedule(CronScheduleBuilder.cronSchedule(schedule))
                .build()
        scheduler.scheduleJob(job, trigger)
    }
}
