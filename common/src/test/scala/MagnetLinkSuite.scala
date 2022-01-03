import com.github.lavrov.bittorrent.*

class MagnetLinkSuite extends munit.FunSuite {

  test("parses magnet link") {
    val link = """magnet:?xt=urn:btih:C071AA6D06101FE3C1D8D3411343CFEB33D91E5F&tr=http%3A%2F%2Fbt.t-ru.org%2Fann%3Fmagnet&dn=%D0%9C%D0%B0%D1%82%D1%80%D0%B8%D1%86%D0%B0%3A%20%D0%92%D0%BE%D1%81%D0%BA%D1%80%D0%B5%D1%88%D0%B5%D0%BD%D0%B8%D0%B5%20%2F%20The%20Matrix%20Resurrections%20(%D0%9B%D0%B0%D0%BD%D0%B0%20%D0%92%D0%B0%D1%87%D0%BE%D0%B2%D1%81%D0%BA%D0%B8%20%2F%20Lana%20Wachowski)%20%5B2021%2C%20%D0%A1%D0%A8%D0%90%2C%20%D0%A4%D0%B0%D0%BD%D1%82%D0%B0%D1%81%D1%82%D0%B8%D0%BA%D0%B0%2C%20%D0%B1%D0%BE%D0%B5%D0%B2%D0%B8%D0%BA%2C%20WEB-DLRip%5D%20MVO%20(Jaskier)%20%2B%20Sub%20Rus%2C%20E"""
    assertEquals(
      MagnetLink.fromString(link),
      Some(
        MagnetLink(
          infoHash = InfoHash.fromString("C071AA6D06101FE3C1D8D3411343CFEB33D91E5F"),
          displayName = Some("Матрица: Воскрешение / The Matrix Resurrections (Лана Вачовски / Lana Wachowski) [2021, США, Фантастика, боевик, WEB-DLRip] MVO (Jaskier) + Sub Rus, E"),
          trackers = List("http://bt.t-ru.org/ann?magnet")
        )
      )
    )
  }
}
