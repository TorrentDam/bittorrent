package logic

import frp.Var

trait Dispatcher {
  def apply(action: Action): Unit
}

object Dispatcher {

  def apply(handler: Handler, state: Var[RootModel]): Dispatcher =
    action => state.set(handler(state.value, action))
}
