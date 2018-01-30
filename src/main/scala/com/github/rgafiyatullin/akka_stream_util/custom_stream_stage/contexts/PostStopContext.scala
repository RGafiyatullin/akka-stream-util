package com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts

import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts.Context.Internals

object PostStopContext {
  def create[Stg <: Stage[Stg]](internals: Internals[Stg]): PostStopContext[Stg] =
    PostStopContext(internals)
}

final case class PostStopContext[Stg <: Stage[Stg]](internals: Internals[Stg])
  extends Context[PostStopContext[Stg], Stg]
{
  type Self = PostStopContext[Stg]

  override def withInternals(i: Internals[Stg]): Self =
    copy(internals = i)
}
