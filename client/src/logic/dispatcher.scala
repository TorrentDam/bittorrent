package logic

import rx.Var

trait Dispatcher {
  def apply(action: Action): Unit
}

object Dispatcher {

  def apply(handler: Handler, state: Var[RootModel]): Dispatcher =
    action => state.update(handler(state.now, action))
}
