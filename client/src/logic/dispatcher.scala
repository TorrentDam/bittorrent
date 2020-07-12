package logic

import monix.reactive.subjects.Var
import monix.execution.Scheduler.Implicits.global

trait Dispatcher {
  def apply(action: Action): Unit
}

object Dispatcher {

  def apply(handler: Handler, state: Var[RootModel]): Dispatcher =
    action => state := handler(state(), action)
}
