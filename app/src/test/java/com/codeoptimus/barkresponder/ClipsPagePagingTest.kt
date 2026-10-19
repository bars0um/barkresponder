package com.codeoptimus.barkresponder

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Loads the real clips page in headless Chromium, served by the real RemoteServer
 * over 120 saved clips, and drives it with a script added to the page. Skipped
 * where no chromium or google-chrome is on PATH.
 */
class ClipsPagePagingTest {
    @get:Rule val tmp = TemporaryFolder()
    private var server: RemoteServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun browser(): String? =
        listOf("chromium", "chromium-browser", "google-chrome", "chrome").firstOrNull { name ->
            System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, name).canExecute() }
        }

    private val driver = """
      <script>
      (async () => {
        window.confirm = () => true;
        const q = (s) => document.querySelector(s);
        const wait = async (cond) => { for (let i = 0; i < 400 && !cond(); i++) await new Promise(r => setTimeout(r, 25)); };
        const snap = (name) => ({
          name,
          rows: document.querySelectorAll("#clips li").length,
          top: q("#pagerTop").hidden ? null : q("#pagerTop .range").textContent,
          bottom: q("#pagerBottom").hidden ? null : q("#pagerBottom .range").textContent,
          prevOff: q("#pagerTop .prev") ? q("#pagerTop .prev").disabled : null,
          nextOff: q("#pagerBottom .next") ? q("#pagerBottom .next").disabled : null,
          summary: q("#summary").textContent,
          labels: [...document.querySelectorAll("#clips .lbl")].map(e => e.textContent).filter(t => t !== "-").length,
        });
        const out = [];
        await wait(() => document.querySelectorAll("#clips li").length > 0);
        out.push(snap("first"));
        q("#pagerBottom .next").click();
        out.push(snap("next from the bottom pager"));
        const firstLi = q("#clips li");
        [...firstLi.querySelectorAll("button")].find(b => b.textContent === "Bark").click();
        await wait(() => q("#clips li .lbl").textContent === "Bark");
        out.push(snap("labelled on page 2"));
        q("#respondedOnly").click();
        out.push(snap("filter on"));
        q("#pagerTop .next").click();
        out.push(snap("filter page 2"));
        q("#respondedOnly").click();
        out.push(snap("filter off"));
        q("#pagerTop .next").click();
        q("#pagerTop .next").click();
        out.push(snap("last page"));
        for (let i = 0; i < 20; i++) {
          const before = document.querySelectorAll("#clips li").length;
          const ids = q("#clips li .time").textContent;
          [...q("#clips li").querySelectorAll("button")].find(b => b.textContent === "Delete").click();
          await wait(() => q("#clips li .time").textContent !== ids || document.querySelectorAll("#clips li").length !== before);
        }
        out.push(snap("after deleting the last page"));
        document.body.setAttribute("data-out", btoa(JSON.stringify(out)));
      })();
      </script>
    """.trimIndent()

    private fun runPage(chrome: String, count: Int, script: String): String {
        val store = SampleStore(tmp.newFolder())
        for (i in 0 until count) {
            store.save(
                FloatArray(320),
                ClipMeta(1_760_000_000_000 + i * 1000L, FloatArray(521), responded = i % 2 == 0, minScoreSetting = 0.4f),
            )!!
        }
        val html = File("src/main/assets/web/index.html").readText().replace("</body>", script + "</body>")
        val srv = RemoteServer(store, html.toByteArray(), onClipsChanged = {})
        srv.start(0, InetAddress.getLoopbackAddress())
        server = srv
        val dump = tmp.newFile()
        val proc = ProcessBuilder(
            chrome, "--headless=new", "--no-sandbox", "--disable-gpu",
            "--user-data-dir=${tmp.newFolder()}", "--virtual-time-budget=60000",
            "--dump-dom", "http://127.0.0.1:${srv.port}/",
        ).redirectOutput(dump).redirectError(File("/dev/null")).start()
        assertTrue("chromium did not finish", proc.waitFor(90, TimeUnit.SECONDS))
        val encoded = Regex("data-out=\"([^\"]+)\"").find(dump.readText())?.groupValues?.get(1)
        assertTrue("the driver script wrote no result", encoded != null)
        srv.stop()
        return String(Base64.getDecoder().decode(encoded))
    }

    private fun pagerScript(extra: String = "") = """
      <script>
      (async () => {
        const q = (s) => document.querySelector(s);
        for (let i = 0; i < 400 && q("#summary").textContent.includes("Loading"); i++) await new Promise(r => setTimeout(r, 25));
        await new Promise(r => setTimeout(r, 200));
        const shown = (id) => getComputedStyle(q(id)).display !== "none";
        document.body.setAttribute("data-out", btoa(JSON.stringify({
          rows: document.querySelectorAll("#clips li").length,
          top: shown("#pagerTop"), bottom: shown("#pagerBottom"),
        })));
      })();
      </script>
    """

    @Test
    fun `shows no pager for 0, 1 or 50 clips and both pagers for 51`() {
        val chrome = browser()
        assumeTrue("no chromium on PATH", chrome != null)
        val seen = listOf(0, 1, 50, 51).map { n ->
            val json = runPage(chrome!!, n, pagerScript())
            n to json
        }.toMap()
        assertEquals("""{"rows":0,"top":false,"bottom":false}""", seen.getValue(0))
        assertEquals("""{"rows":1,"top":false,"bottom":false}""", seen.getValue(1))
        assertEquals("""{"rows":50,"top":false,"bottom":false}""", seen.getValue(50))
        assertEquals("""{"rows":50,"top":true,"bottom":true}""", seen.getValue(51))
    }

    @Test
    fun `pages 50 clips at a time and keeps the place through labels, deletes and the filter`() {
        val chrome = browser()
        assumeTrue("no chromium on PATH", chrome != null)
        val store = SampleStore(tmp.newFolder("samples"))
        for (i in 0 until 120) {
            val at = 1_760_000_000_000 + i * 1000L
            store.save(
                FloatArray(320),
                ClipMeta(at, FloatArray(521), responded = i % 2 == 0, minScoreSetting = 0.4f),
            )!!
        }
        val html = File("src/main/assets/web/index.html").readText().replace("</body>", driver + "</body>")
        val srv = RemoteServer(store, html.toByteArray(), onClipsChanged = {})
        srv.start(0, InetAddress.getLoopbackAddress())
        server = srv

        val dump = tmp.newFile("dom.html")
        val proc = ProcessBuilder(
            chrome, "--headless=new", "--no-sandbox", "--disable-gpu",
            "--user-data-dir=${tmp.newFolder("profile")}", "--virtual-time-budget=60000",
            "--dump-dom", "http://127.0.0.1:${srv.port}/",
        ).redirectOutput(dump).redirectError(File("/dev/null")).start()
        assertTrue("chromium did not finish", proc.waitFor(90, TimeUnit.SECONDS))
        val encoded = Regex("data-out=\"([^\"]+)\"").find(dump.readText())?.groupValues?.get(1)
        assertTrue("the driver script wrote no result", encoded != null)
        val json = String(Base64.getDecoder().decode(encoded))
        val steps = Regex("""\{"name":"([^"]+)","rows":(\d+),"top":(null|"[^"]*"),"bottom":(null|"[^"]*"),"prevOff":(\w+),"nextOff":(\w+),"summary":"([^"]*)","labels":(\d+)\}""")
            .findAll(json).associate { it.groupValues[1] to it.groupValues.drop(2) }
        assertEquals(8, steps.size)

        fun step(name: String) = steps.getValue(name)
        fun range(name: String) = step(name)[1].trim('"') to step(name)[2].trim('"')

        assertEquals("50", step("first")[0])
        assertEquals("1 to 50 of 120" to "1 to 50 of 120", range("first"))
        assertEquals("true", step("first")[3])
        assertEquals("false", step("first")[4])
        assertEquals("120 clips, 0 bark, 0 not bark", step("first")[5])

        assertEquals("51 to 100 of 120" to "51 to 100 of 120", range("next from the bottom pager"))
        assertEquals("50", step("next from the bottom pager")[0])

        assertEquals("51 to 100 of 120" to "51 to 100 of 120", range("labelled on page 2"))
        assertEquals("1", step("labelled on page 2")[6])

        assertEquals("1 to 50 of 60" to "1 to 50 of 60", range("filter on"))
        assertEquals("120 clips, 1 bark, 0 not bark", step("filter on")[5])
        assertEquals("51 to 60 of 60" to "51 to 60 of 60", range("filter page 2"))
        assertEquals("10", step("filter page 2")[0])
        assertEquals("1 to 50 of 120" to "1 to 50 of 120", range("filter off"))

        assertEquals("101 to 120 of 120" to "101 to 120 of 120", range("last page"))
        assertEquals("true", step("last page")[4])

        assertEquals("51 to 100 of 100" to "51 to 100 of 100", range("after deleting the last page"))
        assertEquals("50", step("after deleting the last page")[0])
    }
}
