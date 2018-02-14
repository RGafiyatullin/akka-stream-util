import akka.Done
import akka.stream.{ClosedShape, OverflowStrategy, QueueOfferResult}
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import akka.util.Timeout
import com.github.rgafiyatullin.akka_stream_util.stages.SwitchIn

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

final class SwitchInTest extends TestBase {
  "alt-merge" should "work as identity flow if has a single output" in
    withMaterializer { implicit mat =>
      implicit val ec: ExecutionContext = mat.executionContext
      implicit val askTimeout: Timeout = 10.millis
      futureOk {
        val source = Source.queue[Int](1, OverflowStrategy.fail)
        val sink = Sink.queue[Int]
        val switch = SwitchIn.keys(1).of[Int].toGraph

        val closedGraph = GraphDSL
          .create(source, sink, switch)((_, _, _)) {
            implicit builder => { (sourceShape, sinkShape, intermediateShape) =>
              import GraphDSL.Implicits._

              sourceShape.out ~> intermediateShape.in(1)
                                 intermediateShape.out ~> sinkShape.in

              ClosedShape
          }}
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
    }

  it should "consume elements from the specified inlet" in
    withMaterializer { implicit mat =>
      implicit val ec: ExecutionContext = mat.executionContext
      implicit val askTimeout: Timeout = 10.millis
      futureOk {
        val source = Source.queue[Int](1, OverflowStrategy.fail)
        val sink = Sink.queue[Int]
        val switch = SwitchIn.keys(1,2).of[Int].toGraph

        val closedGraph = GraphDSL
          .create(source, source, sink, switch)((_, _, _,_ )) {
            implicit builder => { (s1, s2, snk, sw) =>
              import GraphDSL.Implicits._

              s1.out ~> sw.in(1)
              s2.out ~> sw.in(2)
                        sw.out ~> snk.in

              ClosedShape
            }
          }

        val (sourceQ1, sourceQ2, sinkQ, apiFut) =
          RunnableGraph.fromGraph(closedGraph).run()

        for {
          api <- apiFut

          _ <- sourceQ1.offer(1).map(_ should be (QueueOfferResult.Enqueued))
          _ <- sinkQ.pull().map(_ should contain (1))

          _ <- api.switch(2)

          _ <- sourceQ2.offer(2).map(_ should be (QueueOfferResult.Enqueued))
          _ <- sinkQ.pull().map(_ should contain (2))

          _ <- api.switch(1)

          _ <- sourceQ1.offer(3).map(_ should be (QueueOfferResult.Enqueued))
          _ <- sinkQ.pull().map(_ should contain (3))

          _ <- api.switch(2)
          _ = sourceQ1.complete()

          _ <- sourceQ2.offer(4).map(_ should be (QueueOfferResult.Enqueued))
          _ <- sinkQ.pull().map(_ should contain (4))

          _ <- api.switch(1)
          _ <- sinkQ.pull().map(_ shouldBe empty)
        }
          yield Done
      }
    }
}
