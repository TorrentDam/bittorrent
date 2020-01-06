package frp

trait Var[A] extends Observable[A] {
  def set(a: A): Unit
}

object Var {

  def apply[A](initial: A): Var[A] = new Var[A] {
    var value: A = initial
    var counter = 0
    val subscribers = scala.collection.mutable.Map.empty[Int, A => Unit]

    def set(a: A): Unit = {
      value = a
      subscribers.values.toList.foreach(f => f(a))
    }

    def subscribe(f: A => Unit): Observable.Unsubscribe = {
      val id = counter
      counter = counter + 1
      subscribers.update(id, f)
      () => subscribers.remove(id)
    }
  }
}
