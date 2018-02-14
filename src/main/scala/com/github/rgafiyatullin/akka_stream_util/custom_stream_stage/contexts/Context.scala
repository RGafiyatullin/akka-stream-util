package com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts

import java.util.NoSuchElementException

import akka.NotUsed
import akka.actor.ActorRef
import akka.event.LoggingAdapter
import akka.stream.{Inlet, Outlet, Shape}
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts.Context._

import scala.concurrent.ExecutionContext
import scala.util.Try

object Context {
  sealed trait InletState
  case object InletEmpty extends InletState
  case object InletToPull extends InletState
  case object InletPulled extends InletState
  final case class InletAvailalbe(value: Any) extends InletState
  case object InletCompleted extends InletState
  final case class InletFailed(reason: Throwable) extends InletState

  sealed trait OutletState
  case object OutletFlushed extends OutletState
  final case class OutletToPush(value: Any) extends OutletState
  case object OutletAvailable extends OutletState
  final case class OutletToFail(reason: Throwable) extends OutletState
  final case class OutletFailed(reason: Throwable) extends OutletState
  case object OutletToComplete extends OutletState
  case object OutletCompleted extends OutletState
  final case class OutletToPushThenComplete(value: Any) extends OutletState
  final case class OutletToPushThenFail(value: Any, reason: Throwable) extends OutletState

  object Internals {
    private def inlets(shape: Shape): Map[Inlet[_], InletState] =
      shape.inlets.map(_ -> InletEmpty).toMap

    private def outlets(shape: Shape): Map[Outlet[_], OutletState] =
      shape.outlets.map(_ -> OutletFlushed).toMap

    def create[Stg <: Stage[Stg]](state: Stg#State, shape: Stg#Shape, gsl: Stage.RunnerLogic): Internals[Stg] =
      Internals(state, inlets(shape), outlets(shape), gsl)
  }

  final case class Internals[Stg <: Stage[Stg]] (
    state: Stg#State,
    private val inlets: Map[Inlet[_], InletState],
    private val outlets: Map[Outlet[_], OutletState],
    graphStageLogic: Stage.RunnerLogic,
    stageComplete: Boolean = false,
    stageFailureOption: Option[Throwable] = None)
  {
    def completeStage(): Internals[Stg] =
      copy(stageComplete = true)

    def failStage(reason: Throwable): Internals[Stg] =
      copy(stageFailureOption = Some(reason))

    def mapInlets(f: (Inlet[_], InletState) => InletState): Internals[Stg] =
      copy(inlets = inlets.map {
        case (key, value) =>
          key -> f(key, value)
      }.toMap)

    def mapOutlets(f: (Outlet[_], OutletState) => OutletState): Internals[Stg] =
      copy(outlets = outlets.map {
        case (key, value) =>
          key -> f(key, value)
      }.toMap)


    def mapFoldInlet[T](key: Inlet[_], f: PartialFunction[InletState, (T, InletState)]): (T, Internals[Stg]) =
      inlets.get(key) match {
        case None =>
          throw new NoSuchElementException("No such inlet: %s".format(key.toString))
        case Some(s0) =>
          require(f.isDefinedAt(s0), "Cannot handle inlet %s while in state: %s".format(key, s0))
          val (ret, s1) = f(s0)
          (ret, copy(inlets = inlets + (key -> s1)))
      }

    def mapFoldOutlet[T](key: Outlet[_], f: PartialFunction[OutletState, (T, OutletState)]): (T, Internals[Stg]) =
      outlets.get(key) match {
        case None =>
          throw new NoSuchElementException("No such outlet: %s".format(key.toString))
        case Some(s0) =>
          require(f.isDefinedAt(s0), "Cannot handle outlet %s while in state: %s".format(key, s0))
          val (ret, s1) = f(s0)
          (ret, copy(outlets = outlets + (key -> s1)))
      }

    type Self = Internals[Stg]

    def withState(s: Stg#State): Self =
      copy(state = s)
  }
}

trait Context[+Self <: Context[Self, Stg], Stg <: Stage[Stg]] {
  def onApply(): Unit = ()

  def withState(s: Stg#State): Self =
    mapInternals(_.withState(s))

  def mapState(f: Stg#State => Stg#State): Self =
    mapInternals(_.withState(f(internals.state)))

  def isEmpty(inlet: Inlet[_]): Boolean =
    checkInletState(inlet)(_ == InletEmpty)

  def isAvailable(inlet: Inlet[_]): Boolean =
    checkInletState(inlet)(_.isInstanceOf[InletAvailalbe])

  def isPulled(inlet: Inlet[_]): Boolean =
    checkInletState(inlet)(_ == InletPulled)

  def isFailed(inlet: Inlet[_]): Boolean =
    checkInletState(inlet)(_.isInstanceOf[InletFailed])

  def failureOption(inlet: Inlet[_]): Option[Throwable] =
    internals.mapFoldInlet(inlet, {
        case asIs @ InletFailed(reason) => (Some(reason), asIs)
        case asIs => (None, asIs)
      })._1


  def isCompleted(inlet: Inlet[_]): Boolean =
    checkInletState(inlet)(_ == InletCompleted)

  def isClosed(inlet: Inlet[_]): Boolean =
    isCompleted(inlet) || isFailed(inlet)



  def isFlushed(outlet: Outlet[_]): Boolean =
    checkOutletState(outlet)(_ == OutletFlushed)

  def isPushed(outlet: Outlet[_]): Boolean =
    checkOutletState(outlet)(_.isInstanceOf[OutletToPush])

  def isAvailable(outlet: Outlet[_]): Boolean =
    checkOutletState(outlet)(_ == OutletAvailable)

  def isCompleted(outlet: Outlet[_]): Boolean =
    checkOutletState(outlet)(_ == OutletCompleted)

  def isFailed(outlet: Outlet[_]): Boolean =
    checkOutletState(outlet)(_.isInstanceOf[OutletFailed])

  def stageActorRefOption: Option[ActorRef] =
    Try(stageActorRef).toOption

  def stageActorRef: ActorRef =
    internals.graphStageLogic.stageActor.ref


  def completeStage(): Self =
    mapInternals(_.completeStage())

  def stageComplete: Boolean =
    internals.stageComplete

  def failStage(reason: Throwable): Self =
    mapInternals(_.failStage(reason))

  def stageFailed: Boolean =
    internals.stageFailureOption.isDefined

  def stageFailureOption: Option[Throwable] =
    internals.stageFailureOption


  def complete(outlet: Outlet[_]): Self =
    mapFoldOutlet(outlet){
      case Context.OutletToPush(value) =>
        (NotUsed, Context.OutletToPushThenComplete(value))

      case outletState if Set[Context.OutletState](
          Context.OutletAvailable,
          Context.OutletFlushed
        ).contains(outletState)
      =>
        (NotUsed, Context.OutletToComplete)
    }._2

  def fail(outlet: Outlet[_], reason: Throwable): Self =
    mapFoldOutlet(outlet) {
      case Context.OutletToPush(value) =>
        (NotUsed, Context.OutletToPushThenFail(value, reason))

      case outletState if Set[Context.OutletState](
          Context.OutletAvailable,
          Context.OutletFlushed
        ).contains(outletState)
      =>
        (NotUsed, Context.OutletToFail(reason))
    }._2

  def pull(inlet: Inlet[_]): Self =
    mapFoldInlet(inlet){
      case Context.InletEmpty =>
        (NotUsed, Context.InletToPull)
    }._2

  def push[T](outlet: Outlet[T], value: T): Self =
    mapFoldOutlet(outlet){
      case Context.OutletAvailable =>
        (NotUsed, Context.OutletToPush(value))
    }._2

  def peek[T](inlet: Inlet[T]): T =
    mapFoldInlet(inlet) {
      case asIs @ InletAvailalbe(value) =>
        (value.asInstanceOf[T], asIs)
    }._1

  def drop(inlet: Inlet[_]): Self =
    mapFoldInlet(inlet){
      case InletAvailalbe(_) =>
        (NotUsed, InletEmpty)
    }._2


  def log: LoggingAdapter =
    internals.graphStageLogic.log

  def executionContext: ExecutionContext =
    internals.graphStageLogic.materializer.executionContext

  def internals: Context.Internals[Stg]
  def withInternals(i: Context.Internals[Stg]): Self

  def mapInternals(f: Context.Internals[Stg] => Context.Internals[Stg]): Self =
    withInternals(f(internals))

  private def mapFoldInternals[T](f: Context.Internals[Stg] => (T, Context.Internals[Stg])): (T, Self) = {
    val (ret, internalsNext) = f(internals)
    (ret, withInternals(internalsNext))
  }

  private def mapFoldInlet[T](key: Inlet[_])(f: PartialFunction[InletState, (T, InletState)]): (T, Self) =
    mapFoldInternals(_.mapFoldInlet(key, f))

  private def mapFoldOutlet[T](key: Outlet[_])(f: PartialFunction[OutletState, (T, OutletState)]): (T, Self) =
    mapFoldInternals(_.mapFoldOutlet(key, f))



  private def checkInletState(key: Inlet[_])(f: InletState => Boolean): Boolean =
    mapFoldInlet(key){
      case s => (f(s), s)
    }._1

  private def checkOutletState(key: Outlet[_])(f: OutletState => Boolean): Boolean =
    mapFoldOutlet(key){
      case s => (f(s), s)
    }._1



}
