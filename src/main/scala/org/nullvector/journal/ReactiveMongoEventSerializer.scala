package org.nullvector.journal

import akka.actor.{Actor, ActorRef, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider, Props}
import akka.persistence.PersistentRepr
import akka.persistence.journal.Tagged
import reactivemongo.bson.BSONDocument

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.reflect.ClassTag

object ReactiveMongoEventSerializer extends ExtensionId[ReactiveMongoEventSerializer] with ExtensionIdProvider {

  override def lookup(): ExtensionId[_ <: Extension] = ReactiveMongoEventSerializer

  override def createExtension(system: ExtendedActorSystem): ReactiveMongoEventSerializer =
    new ReactiveMongoEventSerializer(system)

}

abstract class EventAdapter[E] {

  val payloadType: Class[E]

  val manifest: String

  val tags: Set[String]

  def payloadToBson(payload: E): BSONDocument

  private[journal] def toBson(payload: Any): BSONDocument = payloadToBson(payload.asInstanceOf[E])

  def bsonToPayload(BSONDocument: BSONDocument): E

}

class ReactiveMongoEventSerializer(system: ExtendedActorSystem) extends Extension {

  import system.dispatcher

  private val adapterRegistryRef: ActorRef = system.actorOf(Props(new EventAdapterRegistry()))

  def serialize(persistentRepr: PersistentRepr): Future[(PersistentRepr, Set[String])] =
    persistentRepr.payload match {
      case Tagged(realPayload, tags) =>
        val promise = Promise[(BSONDocument, String, Set[String])]
        adapterRegistryRef ! Serialize(realPayload, promise)
        promise.future.map(r => persistentRepr.withManifest(r._2).withPayload(r._1) -> (tags ++ r._3))

      case payload =>
        val promise = Promise[(BSONDocument, String, Set[String])]
        adapterRegistryRef ! Serialize(payload, promise)
        promise.future.map(r => persistentRepr.withManifest(r._2).withPayload(r._1) -> r._3)
    }

  def deserialize(manifest: String, event: BSONDocument): Future[Any] ={
    val promise = Promise[Any]
    adapterRegistryRef ! Deserialize(manifest, event, promise)
    promise.future

  }


  def addEventAdapter(eventAdapter: EventAdapter[_]): Unit = adapterRegistryRef ! RegisterAdapter(eventAdapter)

  class EventAdapterRegistry extends Actor {

    private val adaptersByType: mutable.HashMap[AdapterKey, EventAdapter[_]] = mutable.HashMap()
    private val adaptersByManifest: mutable.HashMap[String, EventAdapter[_]] = mutable.HashMap()

    override def receive: Receive = {
      case RegisterAdapter(eventAdapter) =>
        adaptersByType += AdapterKey(eventAdapter.payloadType) -> eventAdapter
        adaptersByManifest += eventAdapter.manifest -> eventAdapter

      case Serialize(realPayload, promise) =>
        adaptersByType.get(AdapterKey(realPayload.getClass)) match {
          case Some(adapter) => promise.trySuccess(adapter.toBson(realPayload), adapter.manifest, adapter.tags)
          case None => promise.tryFailure(new Exception(s"There is no an EventAdapter for $realPayload"))
        }

      case Deserialize(manifest, document, promise) =>
        adaptersByManifest.get(manifest) match {
          case Some(adapter) => promise.trySuccess(adapter.bsonToPayload(document))
          case None => promise.tryFailure(new Exception(s"There is no an EventAdapter for $manifest"))
        }
    }

    case class AdapterKey(clazz: Class[_]) {

      override def hashCode(): Int = clazz.getPackage.hashCode()

      override def equals(obj: Any): Boolean = clazz.isAssignableFrom(obj.asInstanceOf[AdapterKey].clazz)
    }

  }

  case class RegisterAdapter(eventAdapter: EventAdapter[_])

  case class Serialize(realPayload: Any, resultPromise: Promise[(BSONDocument, String, Set[String])])

  case class Deserialize(manifest: String, BSONDocument: BSONDocument, promise: Promise[Any])

}

