package com.github.rgafiyatullin.akka_stream_util.stages

import akka.NotUsed
import akka.stream.Attributes.Attribute
import akka.stream.{Attributes, Inlet, Outlet, Shape}
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts.{InletPushedContext, OutletPulledContext, PreStartContext, ReceiveContext}


import scala.collection.immutable
import scala.concurrent.duration._

object ContextTracker {
  def cleanupTickInterval(every: FiniteDuration): Attributes =
    Attributes(CleanupTickInterval(every))

  private case object Tick
  private final case class CleanupTickInterval(every: FiniteDuration) extends Attribute

  object ContextTrackerShape {
    def of[ID, Context, Response]
      (contextInlet: Inlet[(ID, Context)],
       responseInlet: Inlet[(ID, Response)],
       outlet: Outlet[(Response, Context)])
    : ContextTrackerShape[ID, Context, Response] =
      ContextTrackerShape(contextInlet, responseInlet, outlet)
  }

  final case class ContextTrackerShape[ID, Context, Response] private
    (contextInlet: Inlet[(ID, Context)],
     responseInlet: Inlet[(ID, Response)],
     outlet: Outlet[(Response, Context)])
    extends Shape
  {
    override def inlets: immutable.Seq[Inlet[_]] =
      List(contextInlet, responseInlet)

    override def outlets: immutable.Seq[Outlet[_]] =
      List(outlet)

    override def deepCopy(): ContextTrackerShape[ID, Context, Response] =
      ContextTrackerShape.of(contextInlet.carbonCopy(), responseInlet.carbonCopy(), outlet.carbonCopy())
  }


  object State {
    type TickIdx = Long

    def apply[ID, Context, Response]
      (ports: ContextTrackerShape[ID, Context, Response],
       cleanupInterval: FiniteDuration)
    : State[ID, Context, Response] =
      State(
        ports,
        contexts = Map.empty,
        currentTick = 0,
        cleanupInterval)
  }

  final case class State[ID, Context, Response] private
    (ports: ContextTrackerShape[ID, Context, Response],
     contexts: Map[ID, (Context, State.TickIdx)],
     currentTick: State.TickIdx,
     cleanupInterval: FiniteDuration)
    extends Stage.State[ContextTracker[ID, Context, Response]]
  {
    import State.TickIdx

    def addContext(id: ID, context: Context): State[ID, Context, Response] =
      copy(contexts = contexts + (id -> (context, currentTick)))

    def dropContext(id: ID): State[ID, Context, Response] =
      copy(contexts = contexts - id)

    def getContext(id: ID): Option[Context] =
      contexts.get(id).map(_._1)

    def dropContextsOlderThan(tickIdx: TickIdx): State[ID, Context, Response] =
      copy(contexts = contexts.filterNot(_._2._2 < tickIdx))

    def withTickIdx(idx: TickIdx): State[ID, Context, Response] =
      copy(currentTick = idx)


    override def receiveEnabled: Boolean = true

    override def preStart
      (ctx: PreStartContext[ContextTracker[ID, Context, Response]])
    : PreStartContext[ContextTracker[ID, Context, Response]] = {
      ctx.scheduler.scheduleOnce(cleanupInterval, ctx.stageActorRef, Tick)(ctx.executionContext)
      ctx.pull(ports.contextInlet)
    }

    override def receive
      (ctx: ReceiveContext.NotReplied[ContextTracker[ID, Context, Response]])
    : ReceiveContext[ContextTracker[ID, Context, Response]] =
      ctx.handleWith {
        case Tick =>
          ctx.scheduler.scheduleOnce(cleanupInterval, ctx.stageActorRef, Tick)(ctx.executionContext)
          ctx.withState(
            dropContextsOlderThan(currentTick).withTickIdx(currentTick + 1))
      }

    override def outletOnPull
      (ctx: OutletPulledContext[ContextTracker[ID, Context, Response]])
    : OutletPulledContext[ContextTracker[ID, Context, Response]] =
      ctx.pull(ports.responseInlet)

    override def inletOnPush
      (ctx: InletPushedContext[ContextTracker[ID, Context, Response]])
    : InletPushedContext[ContextTracker[ID, Context, Response]] =
      ctx.port match {
        case ports.contextInlet =>
          val (id, context) = ctx.peek(ports.contextInlet)
          val stateNext = addContext(id, context)
          ctx
            .drop(ports.contextInlet)
            .pull(ports.contextInlet)
            .withState(stateNext)

        case ports.responseInlet =>
          val (id, response) = ctx.peek(ports.responseInlet)
          val ctxDropped = ctx.drop(ports.responseInlet)
          getContext(id) match {
            case None =>
              ctxDropped.log.warning("Cannot recover context for ID: {} [response: {}]", id, response)
              ctxDropped.pull(ports.responseInlet)

            case Some(context) =>
              ctxDropped
                .push(ports.outlet, (response, context))
          }
      }
  }

}

final case class ContextTracker[ID, Context, Response]()
  extends Stage[ContextTracker[ID, Context, Response]]
{
  override type Shape = ContextTracker.ContextTrackerShape[ID, Context, Response]
  override type State = ContextTracker.State[ID, Context, Response]
  override type MatValue = NotUsed

  override val shape: this.type#Shape =
    ContextTracker.ContextTrackerShape.of(
      Inlet("context-inlet"),
      Inlet("response-inlet"),
      Outlet("outlet"))

  override def initialStateAndMatValue
    (logic: Stage.RunnerLogic,
     inheritedAttributes: Attributes)
  : (ContextTracker.State[ID, Context, Response], NotUsed) = {
    val cleanupTickInterval: FiniteDuration =
      inheritedAttributes
        .get[ContextTracker.CleanupTickInterval]
        .map(_.every)
        .getOrElse(5.seconds)

    (ContextTracker.State(shape, cleanupTickInterval), NotUsed)
  }
}