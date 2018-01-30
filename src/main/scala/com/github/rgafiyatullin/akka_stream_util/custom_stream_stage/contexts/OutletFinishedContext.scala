package com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts

import akka.stream.Outlet
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage

object OutletFinishedContext {
  def create[Stg <: Stage[Stg]](outlet: Outlet[_], internals: Context.Internals[Stg]): OutletFinishedContext[Stg] =
    OutletFinishedContext(internals, outlet)
}

final case class OutletFinishedContext[Stg <: Stage[Stg]](
  internals: Context.Internals[Stg],
  outlet: Outlet[_])
    extends Context[OutletFinishedContext[Stg], Stg]
{
  type Self = OutletFinishedContext[Stg]

  override def withInternals(i: Context.Internals[Stg]): Self =
    copy(internals = i)
}
