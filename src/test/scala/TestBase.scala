import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Milliseconds, Span}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

trait TestBase extends FlatSpec with Matchers with ScalaFutures {
  override implicit def patienceConfig: PatienceConfig =
    PatienceConfig(Span(100, Milliseconds), Span(10, Milliseconds))

  def withActorSystem[T](f: ActorSystem => Future[T]): Future[T] = {
    val actorSystem = ActorSystem()
    try f(actorSystem)
    finally {
      actorSystem.terminate()
      ()
    }
  }

  def withMaterializer[T](f: ActorMaterializer => Future[T]): Future[T] =
    withActorSystem { actorSystem =>
      val mat = ActorMaterializer()(actorSystem)
      f(mat)
    }

  def futureOk[T](f: Future[T]): Future[T] = {
    val _ = Await.result(f, 5.seconds)
    f
  }
}
