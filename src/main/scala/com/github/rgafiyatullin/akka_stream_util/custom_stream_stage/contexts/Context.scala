package com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts

import java.util.NoSuchElementException

import akka.NotUsed
import akka.stream.{Inlet, Outlet, Shape}
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts.Context._

object Context {
  sealed trait InletState
  case object InletEmpty extends InletState
  case object InletToPull extends InletState
  case object InletPulled extends InletState
  final case class InletAvailalbe(value: Any) extends InletState

  sealed trait OutletState
  case object OutletFlushed extends OutletState
  final case class OutletPushed(value: Any) extends OutletState
  case object OutletAvailable extends OutletState

  object Internals {
    private def inlets(shape: Shape): Map[Inlet[_], InletState] =
      shape.inlets.map(_ -> InletEmpty).toMap

    private def outlets(shape: Shape): Map[Outlet[_], OutletState] =
      shape.outlets.map(_ -> OutletFlushed).toMap

    def create[Stg <: Stage[Stg]](state: Stg#State, shape: Stg#Shape): Internals[Stg] =
      Internals(state, inlets(shape), outlets(shape))
  }

  final case class Internals[Stg <: Stage[Stg]] private (
    state: Stg#State,
    inlets: Map[Inlet[_], InletState],
    outlets: Map[Outlet[_], OutletState])
  {
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

trait Context[Self <: Context[Self, Stg], Stg <: Stage[Stg]] {
  def internals: Context.Internals[Stg]
  def withInternals(i: Context.Internals[Stg]): Self

  def mapInternals(f: Context.Internals[Stg] => Context.Internals[Stg]): Self =
    withInternals(f(internals))

  def mapFoldInternals[T](f: Context.Internals[Stg] => (T, Context.Internals[Stg])): (T, Self) = {
    val (ret, internalsNext) = f(internals)
    (ret, withInternals(internalsNext))
  }

  def withState(s: Stg#State): Self =
    mapInternals(_.withState(s))

  def state: Stg#State =
    internals.state

  private def mapFoldInlet[T](key: Inlet[_])(f: PartialFunction[InletState, (T, InletState)]): (T, Self) =
    mapFoldInternals(_.mapFoldInlet(key, f))

  private def mapFoldOutlet[T](key: Outlet[_])(f: PartialFunction[OutletState, (T, OutletState)]): (T, Self) = {
    mapFoldInternals(_.mapFoldOutlet(key, f))
  }


  private def checkInletState(key: Inlet[_])(f: InletState => Boolean): Boolean =
    mapFoldInlet(key){
      case s => (f(s), s)
    }._1

  private def checkOutletState(key: Outlet[_])(f: OutletState => Boolean): Boolean =
    mapFoldOutlet(key){
      case s => (f(s), s)
    }._1


  def isEmpty(inlet: Inlet[_]): Boolean =
    checkInletState(inlet)(_ == InletEmpty)

  def isAvailable(inlet: Inlet[_]): Boolean =
    checkInletState(inlet)(_.isInstanceOf[InletAvailalbe])

  def isPulled(inlet: Inlet[_]): Boolean =
    checkInletState(inlet)(_ == InletPulled)

  def isFlushed(outlet: Outlet[_]): Boolean =
    checkOutletState(outlet)(_ == OutletFlushed)

  def isPushed(outlet: Outlet[_]): Boolean =
    checkOutletState(outlet)(_.isInstanceOf[OutletPushed])

  def isAvailable(outlet: Outlet[_]): Boolean =
    checkOutletState(outlet)(_ == OutletAvailable)



  def pull(inlet: Inlet[_]): Self =
    mapFoldInlet(inlet){
      case Context.InletEmpty =>
        (NotUsed, Context.InletToPull)
    }._2

  def push[T](outlet: Outlet[T], value: T): Self =
    mapFoldOutlet(outlet){
      case Context.OutletAvailable =>
        (NotUsed, Context.OutletPushed(value))
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

}
