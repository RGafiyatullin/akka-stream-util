package com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts

import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts.Context.Internals

object PreStartContext {
  def create[Stg <: Stage[Stg]](internals: Internals[Stg]): PreStartContext[Stg] =
    PreStartContext(internals)
}

final case class PreStartContext[Stg <: Stage[Stg]] private(internals: Internals[Stg])
  extends Context[PreStartContext[Stg], Stg]
{
  type Self = PreStartContext[Stg]

  override def withInternals(i: Internals[Stg]): Self =
    copy(internals = i)
}
