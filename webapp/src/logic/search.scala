package logic

import scala.concurrent.{Future, Promise}

object SearchApi {

  def apply(query: String): Future[SearchResults] = {

    val request = new org.scalajs.dom.XMLHttpRequest
    request.open("GET", environment.httpUrl(s"/search?query=$query"))
    request.send()
    val promise = Promise[SearchResults]
    request.onloadend = { _ =>
      if (request.status == 200) {
        val results = SearchResults.fromJson(request.responseText)
        promise.success(results)
      }
    }
    promise.future
  }

  case class SearchResults(results: List[SearchResults.Item])

  object SearchResults {

    import upickle.default._

    case class Item(title: String, magnet: String)

    implicit val itemReader: ReadWriter[Item] = macroRW
    implicit val resultsReader: ReadWriter[SearchResults] = macroRW

    def fromJson(json: String): SearchResults = read[SearchResults](json)
  }
}
