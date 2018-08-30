package tests

import akka.stream.ClosedShape
import akka.stream.scaladsl.{Broadcast, GraphDSL, RunnableGraph, Sink, Source}
import com.github.rgafiyatullin.akka_stream_util.stages.ContextTracker

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

final class ContextTrackerTest extends TestBase {
  "context tracker" should "match associated contexts correctly" in
    unit(withMaterializer{ mat =>
      futureOk {
        implicit val ec: ExecutionContext = mat.executionContext
        val scheduler = mat.system.scheduler

        val checkSink =
          Sink.foreach[(Int, Int)]{ case (n, h) => val _ = n.hashCode should be(h) }

        val runnableGraph =
          RunnableGraph fromGraph GraphDSL.create(checkSink) { implicit builder => sinkShape =>
            import GraphDSL.Implicits._

            val bc = builder add Broadcast[(Int, Int, Int)](2, eagerCancel = true)
            val ct = builder add
              ContextTracker[Int, Int, Int]()
                .toGraph
                .withAttributes(ContextTracker.cleanupTickInterval(100.millis))

            val srcShape = builder add
              Source(1 to 10000).map { n => (n, n.hashCode, n.hashCode % 50) }

            srcShape.out ~> bc.in
                            bc.out(0).map { case (n, h, _) => (n, h) } ~> ct.contextInlet
                            bc.out(1).mapAsyncUnordered(100) { case (n, _, d) =>
                                akka.pattern.after(d.millis, scheduler)(Future successful (n, n))
                              }                                        ~> ct.responseInlet

            ct.outlet ~> sinkShape.in

            ClosedShape
          }
        runnableGraph.run()(mat)
      }
    })
}
