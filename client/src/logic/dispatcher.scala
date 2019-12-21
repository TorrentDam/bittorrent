package logic

trait Dispatcher {
  def apply(action: Action): Unit
}
