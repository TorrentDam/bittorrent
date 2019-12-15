trait Observed[A] { self =>
  def value: A
  def subscribe(f: A => Unit): Observed.Unsubscribe
  def zoomTo[B](f: A => B): Observed[B] = new Observed[B] {
    def value: B = f(self.value)
    def subscribe(f0: B => Unit): Observed.Unsubscribe = self.subscribe(a => f0(f(a)))
  }
}

object Observed {
  type Unsubscribe = () => Unit
}

trait Var[A] extends Observed[A] {
  def set(a: A): Unit
}

object Var {

  def apply[A](initial: A): Var[A] = new Var[A] {
    var value: A = initial
    var counter = 0
    val subscribers = scala.collection.mutable.Map.empty[Int, A => Unit]

    def set(a: A): Unit = {
      value = a
      subscribers.values.foreach(f => f(a))
    }

    def subscribe(f: A => Unit): Observed.Unsubscribe = {
      val id = counter
      counter = counter + 1
      subscribers.update(id, f)
      () => subscribers.remove(id)
    }
  }
}
