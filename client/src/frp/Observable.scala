package frp

trait Observable[A] { self =>
  def value: A
  def subscribe(f: A => Unit): Observable.Unsubscribe
  def zoomTo[B](f: A => B): Observable[B] = new Observable[B] {
    def value: B = f(self.value)
    def subscribe(f0: B => Unit): Observable.Unsubscribe = self.subscribe(a => f0(f(a)))
  }
}
object Observable {
  type Unsubscribe = () => Unit
}
