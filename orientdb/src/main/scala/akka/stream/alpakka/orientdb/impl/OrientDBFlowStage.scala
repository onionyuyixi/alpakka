/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.orientdb.impl

import akka.NotUsed
import akka.annotation.InternalApi
import akka.stream.alpakka.orientdb.{OIncomingMessage, OrientDBUpdateSettings}
import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import com.orientechnologies.orient.`object`.db.OObjectDatabaseTx
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx
import com.orientechnologies.orient.core.record.ORecord
import com.orientechnologies.orient.core.record.impl.ODocument
import com.orientechnologies.orient.core.tx.OTransaction

import scala.collection.mutable
import scala.collection.immutable

/**
 * INTERNAL API
 */
@InternalApi
private[orientdb] class OrientDBFlowStage[T, C](
    className: String,
    settings: OrientDBUpdateSettings,
    clazz: Option[Class[T]]
) extends GraphStage[FlowShape[OIncomingMessage[T, C], immutable.Seq[OIncomingMessage[T, C]]]] {

  private val in = Inlet[OIncomingMessage[T, C]]("messages")
  private val out = Outlet[immutable.Seq[OIncomingMessage[T, C]]]("failed")
  override val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler {

      private val queue = new mutable.Queue[OIncomingMessage[T, C]]()
      private val failureHandler = getAsyncCallback[(immutable.Seq[OIncomingMessage[T, C]], Throwable)](handleFailure)
      private val responseHandler =
        getAsyncCallback[(immutable.Seq[OIncomingMessage[T, C]], Option[String])](handleResponse)
      private var failedMessages: immutable.Seq[OIncomingMessage[T, C]] = Nil
      private var retryCount: Int = 0

      private var client: ODatabaseDocumentTx = _
      private var oObjectClient: OObjectDatabaseTx = _

      override def preStart(): Unit = {
        client = settings.oDatabasePool.acquire()
        oObjectClient = new OObjectDatabaseTx(client)
        pull(in)
      }

      override def postStop(): Unit = {
        oObjectClient.close()
        client.close()
      }

      private def tryPull(): Unit =
        if (queue.size < settings.bufferSize && !isClosed(in) && !hasBeenPulled(in)) {
          pull(in)
        }

      override def onTimer(timerKey: Any): Unit = {
        sendOSQLBulkInsertRequest(failedMessages)
        failedMessages = Nil
      }

      private def handleFailure(args: (immutable.Seq[OIncomingMessage[T, C]], Throwable)): Unit = {
        val (messages, exception) = args
        if (retryCount >= settings.maxRetry) {
          failStage(exception)
        } else {
          retryCount = retryCount + 1
          failedMessages = messages
          scheduleOnce(NotUsed, settings.retryInterval)
        }
      }

      private def handleSuccess(): Unit =
        completeStage()

      private def handleResponse(args: (immutable.Seq[OIncomingMessage[T, C]], Option[String])): Unit = {
        retryCount = 0
        val (messages, error) = args

        val failedMessages = messages.flatMap {
          case message =>
            if (error.isEmpty) {
              None
            } else {
              Some(message)
            }
        }

        val nextMessages = (1 to settings.bufferSize).flatMap { _ =>
          queue.dequeueFirst(_ => true)
        }

        if (nextMessages.isEmpty) {
          handleSuccess()
        } else {
          sendOSQLBulkInsertRequest(nextMessages)
        }

        push(out, failedMessages)
      }

      private def sendOSQLBulkInsertRequest(messages: immutable.Seq[OIncomingMessage[T, C]]): Unit =
        try {
          ODatabaseRecordThreadLocal.instance().set(client)
          if (clazz.isEmpty) {
            if (!client.getMetadata.getSchema.existsClass(className)) {
              client.getMetadata.getSchema.createClass(className)
            }
            client.begin(OTransaction.TXTYPE.OPTIMISTIC)

            var faultyMessages: List[OIncomingMessage[T, C]] = List()
            var successfulMessages: List[OIncomingMessage[T, C]] = List()
            messages.foreach {
              case OIncomingMessage(oDocument: ODocument, passThrough: C) =>
                val document = new ODocument()
                oDocument
                  .asInstanceOf[ODocument]
                  .fieldNames()
                  .zip(oDocument.asInstanceOf[ODocument].fieldValues())
                  .foreach {
                    case (fieldName, fieldVal) =>
                      document.field(fieldName, fieldVal)
                      ()
                  }
                document.setClassName(className)
                client.save(document)
                successfulMessages = successfulMessages ++ List(
                  OIncomingMessage(oDocument.asInstanceOf[T], passThrough)
                )
                ()
              case OIncomingMessage(oRecord: ORecord, passThrough: C) =>
                client.save(oRecord)
                successfulMessages = successfulMessages ++ List(OIncomingMessage(oRecord.asInstanceOf[T], passThrough))
                ()
              case OIncomingMessage(others: AnyRef, passThrough: C) =>
                faultyMessages = faultyMessages ++ List(OIncomingMessage(others.asInstanceOf[T], passThrough))
                ()
            }
            client.commit()
            if (faultyMessages.nonEmpty) {
              responseHandler.invoke((faultyMessages, Some("Records are invalid OrientDB Records")))
            } else {
              emit(out, successfulMessages)
              responseHandler.invoke((immutable.Seq.empty, None))
            }
          } else {
            client.setDatabaseOwner(oObjectClient)
            oObjectClient.getEntityManager.registerEntityClass(
              clazz.getOrElse(throw new RuntimeException("Typed stream class is invalid"))
            )

            var faultyMessages: List[OIncomingMessage[T, C]] = List()
            var successfulMessages: List[OIncomingMessage[T, C]] = List()
            messages.foreach {
              case OIncomingMessage(typeRecord: T, passThrough: C) =>
                oObjectClient.save(typeRecord)
                successfulMessages = successfulMessages ++ List(OIncomingMessage(typeRecord, passThrough))
                ()
              case OIncomingMessage(others: AnyRef, passThrough: C) =>
                faultyMessages = faultyMessages ++ List(OIncomingMessage(others.asInstanceOf[T], passThrough))
                ()
            }

            if (faultyMessages.nonEmpty) {
              responseHandler.invoke((faultyMessages, Some("Records are invalid OrientDB Records")))
            } else {
              emit(out, successfulMessages)
              responseHandler.invoke((immutable.Seq.empty, None))
            }
          }
        } catch {
          case exception: Exception =>
            failureHandler.invoke((messages, exception))
        }

      setHandlers(in, out, this)

      override def onPull(): Unit = tryPull()

      override def onPush(): Unit = {
        val message = grab(in)
        queue.enqueue(message)

        val messages = (1 to settings.bufferSize).flatMap { _ =>
          queue.dequeueFirst(_ => true)
        }
        sendOSQLBulkInsertRequest(messages)

        tryPull()
      }

      override def onUpstreamFailure(exception: Throwable): Unit =
        failStage(exception)

      override def onUpstreamFinish(): Unit = handleSuccess()
    }
}
