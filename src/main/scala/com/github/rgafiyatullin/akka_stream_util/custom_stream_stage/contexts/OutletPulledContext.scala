package com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts

import akka.NotUsed
import akka.stream.Outlet
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage

object OutletPulledContext {
  def create[Stg <: Stage[Stg]](outlet: Outlet[_], internals: Context.Internals[Stg]): OutletPulledContext[Stg] =
    OutletPulledContext(outlet, internals.mapFoldOutlet(outlet, {
      case Context.OutletFlushed =>
        (NotUsed, Context.OutletAvailable)
    })._2)
}

final case class OutletPulledContext[Stg <: Stage[Stg]] private (outlet: Outlet[_], internals: Context.Internals[Stg])
  extends Context[OutletPulledContext[Stg], Stg]
{
  type Self = OutletPulledContext[Stg]

  def port: Outlet[_] = outlet

  override def withInternals(i: Context.Internals[Stg]): Self =
    copy(internals = i)
}
