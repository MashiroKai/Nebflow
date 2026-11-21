package nebflow.core.tools

import munit.FunSuite

/**
 * WebFetch "soft failure reported as success" fix (2026-11-22 audit,
 * .stand/tmp/websearch-eval/probe3.log): 4 of 8 fetches returned `OK` for pages
 * that carried no usable content — an Earthdata login form (459 chars), an IEEE
 * "JavaScript is disabled" wall (178 chars), an Elsevier `# Redirecting` stub
 * (15 chars) and a cddis "This page has been moved" notice (217 chars).
 *
 * Every sample below was re-captured live on 2026-11-22 through the pre-fix
 * tool (each returned `OK` at the time), so the cases are real payloads, not
 * paraphrases. Negative controls pin that real content still comes back OK.
 */
class WebFetchToolSoftFailureSpec extends FunSuite:

  private val html = "text/html;charset=UTF-8"

  /** Live capture 2026-11-22: `https://data.gesdisc.earthdata.nasa.gov/…/MERRA2_400.…nc4` → OK, 459 chars. */
  private val earthdataLogin =
    """Earthdata Login
      |
      |> Earthdata Login
      |
      |NASA GESDISC DATA ARCHIVE
      |
      |Username
      |
      |Password
      |
      |Stay signed in (this is a private workstation)
      |
      |Need Help?
      |
      |• Forgot Username
      |
      |• Forgot Password""".stripMargin

  /** Live capture 2026-11-22: `https://ieeexplore.ieee.org/document/7883558` → OK, 178 chars. */
  private val ieeeJsWall =
    """JavaScript is disabled
      |
      |        In order to continue, we need to verify that you're not a robot.
      |        This requires JavaScript. Enable JavaScript and then reload the page.""".stripMargin

  /** Live capture 2026-11-22: `https://doi.org/10.1016/j.asr.2014.05.006` → OK, 15 chars, title "Redirecting". */
  private val elsevierRedirectTitle = Option("Redirecting")
  private val elsevierRedirectText = ""

  /** Live capture 2026-11-22: cddis data page → OK, 217 chars. */
  private val cddisMoved =
    "This page has been moved. If you are not redirected within 3 seconds, click here to go to the new " +
      "GNSS Ionospheric and Tropospheric Products page on the Earthdata website."

  // ── NEGATIVE CONTROL: the four real soft failures must FAIL ─────────

  test("negative control: Earthdata login form → soft-failure: login-wall (was OK 459 chars)") {
    val f = WebFetchTool.detectSoftFailure(Option("Earthdata Login"), earthdataLogin, html)
    assert(f.isDefined, "a login form is not the requested .nc4 file")
    assertEquals(f.get.tag, "soft-failure: login-wall")
    assert(f.get.cause.nonEmpty && f.get.suggestion.nonEmpty, "failure must carry cause + next step")
  }

  test("negative control: IEEE JavaScript wall → soft-failure: js-required (was OK 178 chars)") {
    val f = WebFetchTool.detectSoftFailure(None, ieeeJsWall, html)
    assert(f.isDefined, "a JS challenge page is not the article")
    assertEquals(f.get.tag, "soft-failure: js-required")
  }

  test("negative control: Elsevier redirect stub → soft-failure: redirect-loop (was OK 15 chars)") {
    val f = WebFetchTool.detectSoftFailure(elsevierRedirectTitle, elsevierRedirectText, html)
    assert(f.isDefined, "a 15-char redirect shell is not the paper")
    assertEquals(f.get.tag, "soft-failure: redirect-loop")
  }

  test("negative control: cddis moved notice → soft-failure: moved (was OK 217 chars)") {
    val f = WebFetchTool.detectSoftFailure(Option("GNSS Ionospheric and Tropospheric Products"), cddisMoved, html)
    assert(f.isDefined, "a moved notice is not the data page")
    assertEquals(f.get.tag, "soft-failure: moved")
  }

  test("generic floor: a near-empty HTML page is failed (thin-content), not reported as a read") {
    val f = WebFetchTool.detectSoftFailure(Option(""), "OK", html)
    assert(f.isDefined, "'OK' is not content")
    assertEquals(f.get.tag, "soft-failure: thin-content")
    assert(f.get.suggestion.nonEmpty)
  }

  // ── NEGATIVE CONTROL: real content must stay OK ─────────────────────

  /** arXiv abs/1706.03762 abstract page — the run's clean success (8837 chars). */
  private val arxivAbstract =
    """Attention Is All You Need
      |Ashish Vaswani, Noam Shazeer, Niki Parmar, Jakob Uszkoreit, Llion Jones, Aidan N. Gomez,
      |Łukasz Kaiser, Illia Polosukhin
      |
      |Abstract: The dominant sequence transduction models are based on complex recurrent or
      |convolutional neural networks that include an encoder and a decoder. The best performing
      |models also connect the encoder and decoder through an attention mechanism. We propose a new
      |simple network architecture, the Transformer, based solely on attention mechanisms,
      |dispensing with recurrence and convolutions entirely. Experiments on two machine translation
      |tasks show these models to be superior in quality while being more parallelizable and
      |requiring significantly less time to train. Our model achieves 28.4 BLEU on the WMT 2014
      |English-to-German translation task, improving over the existing best results, including
      |ensembles, by over 2 BLEU. On the WMT 2014 English-to-French translation task, our model
      |establishes a new single-model state-of-the-art BLEU score of 41.8 after training for 3.5
      |days on eight GPUs, a small fraction of the training costs of the best models from the
      |literature. We show that the Transformer generalizes well to other tasks by applying it
      |successfully to English constituency parsing both with large and limited training data.
      |
      |Comments: 15 pages, 5 figures
      |Subjects: Computation and Language (cs.CL); Machine Learning (cs.LG)
      |Cite as: arXiv:1706.03762 [cs.CL]
      |Submission history: Submitted on 12 Jun 2017 (v1), last revised 2 Aug 2023 (this version, v7)
      |Journal reference: Advances in Neural Information Processing Systems 30 (NIPS 2017)""".stripMargin

  test("negative control: real arXiv abstract page stays OK") {
    assertEquals(WebFetchTool.detectSoftFailure(Option("Attention Is All You Need"), arxivAbstract, html), None)
  }

  /** Real NOAA SWPC K-index JSON (6.9 KB in the run) — structured payload, small is fine. */
  private val noaaJson =
    "[" + (1 to 40)
      .map(i => s"""{"time_tag":"2026-11-21T0${i % 9}:00:00","kp":${i % 9}, "a_running":${i * 2}, "station_count":8}""")
      .mkString(",") + "]"

  test("negative control: real JSON payload stays OK (structured data has no thin-content floor)") {
    assertEquals(WebFetchTool.detectSoftFailure(None, noaaJson, "application/json"), None)
  }

  test("negative control: a long article that merely mentions a login/JS phrase stays OK") {
    val longArticle = ("Scala 3 migration notes. " + "Body paragraph about the compiler. " * 200) +
      " If the site asks you to sign in, use the API instead. Enable JavaScript in your browser to see the demo."
    assertEquals(WebFetchTool.detectSoftFailure(Option("Migration guide"), longArticle, html), None)
    assert(longArticle.length > WebFetchTool.SOFT_FAILURE_MAX_CHARS, "sample must exceed the interstitial ceiling")
  }

  test("thresholds stay in the interstitial band (documented in WebFetchTool)") {
    assert(WebFetchTool.SOFT_FAILURE_MAX_CHARS > 1_000, "real content pages must clear the JS/login ceiling")
    assert(WebFetchTool.MIN_HTML_CONTENT_CHARS >= 100, "the 15-char redirect stub must fail the floor")
    assert(WebFetchTool.REDIRECT_SHELL_MAX_CHARS < WebFetchTool.MOVED_NOTICE_MAX_CHARS)
  }

end WebFetchToolSoftFailureSpec
