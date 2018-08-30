package tests

import akka.Done
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import akka.stream.{ClosedShape, OverflowStrategy, QueueOfferResult}
import akka.util.Timeout
import com.github.rgafiyatullin.akka_stream_util.stages.SwitchOut

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

final class SwitchOutTest extends TestBase {
  "AltCast" should "work as identity flow in case of a single outlet" in
    unit(withMaterializer { implicit mat =>
      implicit val ec: ExecutionContext = mat.executionContext
      implicit val askTimeout: Timeout = 10.millis

      futureOk {
        val source = Source.queue[Int](1, OverflowStrategy.fail)
        val sink = Sink.queue[Int]
        val switch = SwitchOut.keys(1).of[Int].toGraph

        val closedGraph = GraphDSL.create(source, sink, switch)((_, _, _)) {
          implicit builder => (src, snk, sw) =>
            import GraphDSL.Implicits._

            src ~> sw.in
                   sw.out(1) ~> snk.in

            ClosedShape
        }

        val (sourceQ, sinkQ, apiFut) = RunnableGraph.fromGraph(closedGraph).run()

        for {
          _ <- apiFut

          _ <- sourceQ.offer(1).map(_ should be (QueueOfferResult.Enqueued))
          _ <- sinkQ.pull().map(_ should contain (1))

          _ <- sourceQ.offer(2).map(_ should be (QueueOfferResult.Enqueued))
          _ <- sinkQ.pull().map(_ should contain (2))

          _ <- sourceQ.offer(3).map(_ should be (QueueOfferResult.Enqueued))
          _ <- sinkQ.pull().map(_ should contain (3))

          _ <- sourceQ.offer(4).map(_ should be (QueueOfferResult.Enqueued))
          _ <- sinkQ.pull().map(_ should contain (4))

          _ = sourceQ.complete()
          _ <- sinkQ.pull().map(_ shouldBe empty)
        }
          yield Done
      }
    })

  it should "route items to the specified outlets" in
    unit(withMaterializer { implicit mat =>
      implicit val ec: ExecutionContext = mat.executionContext
      implicit val askTimeout: Timeout = 10.millis

      futureOk {
        val source = Source.queue[Int](1, OverflowStrategy.fail)
        val sink = Sink.queue[Int]
        val switch = SwitchOut.keys(1, 2).of[Int].toGraph

        val closedGraph = GraphDSL.create(source, sink, sink, switch)((_, _, _, _)) {
          implicit builder => (src, snk1, snk2, sw) =>
            import GraphDSL.Implicits._

            src ~> sw.in
                   sw.out(1) ~> snk1.in
                   sw.out(2) ~> snk2.in

            ClosedShape
        }

        val (sourceQ, sinkQ1, sinkQ2, apiFut) = RunnableGraph.fromGraph(closedGraph).run()

        for {
          api <- apiFut

          _ <- sourceQ.offer(1).map(_ should be (QueueOfferResult.Enqueued))
          _ <- sinkQ1.pull().map(_ should contain (1))

          _ <- api.switch(2)

          _ <- sourceQ.offer(2).map(_ should be (QueueOfferResult.Enqueued))
          _ <- sinkQ2.pull().map(_ should contain (2))

          _ <- api.switch(1)

          _ <- sourceQ.offer(3).map(_ should be (QueueOfferResult.Enqueued))
          _ <- sinkQ1.pull().map(_ should contain (3))

          _ = sourceQ.complete()
          _ <- sinkQ1.pull().map(_ shouldBe empty)
          _ <- sinkQ2.pull().map(_ shouldBe empty)
        }
          yield Done
      }
    })
}
