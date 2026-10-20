import java.net.*; import java.net.http.*; import java.nio.file.*; import java.util.*; import java.util.concurrent.*; import java.util.concurrent.atomic.*;
public class Traffic {
  static HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager()).build(); }
  static WebSocket open(HttpClient h, int port, AtomicLong bytes, AtomicLong frames, BlockingQueue<String> q) throws Exception {
    HttpResponse<String> r = h.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build(), HttpResponse.BodyHandlers.ofString());
    String c = r.headers().allValues("set-cookie").get(0).split(";")[0];
    return h.newWebSocketBuilder().header("Cookie", c).buildAsync(URI.create("ws://127.0.0.1:" + port + "/WS/?source=probe&platform=probe"), new WebSocket.Listener() {
      StringBuilder sb = new StringBuilder();
      public CompletionStage<?> onText(WebSocket w, CharSequence d, boolean last) { sb.append(d); if (last) { bytes.addAndGet(sb.length()); frames.incrementAndGet(); if (q != null) q.add(sb.toString()); sb.setLength(0); } w.request(1); return null; }
    }).get(10, TimeUnit.SECONDS);
  }
  static String json(List<String> paths) { StringBuilder s = new StringBuilder("{\"action\":\"Register\",\"paths\":["); for (int i = 0; i < paths.size(); i++) s.append(i > 0 ? "," : "").append('"').append(paths.get(i).replace("\"", "\\\"")).append('"'); return s.append("]}").toString(); }
  public static void main(String[] a) throws Exception {
    int port = Integer.parseInt(a[0]); String game = a[1];
    Map<String, List<String>> sets = new LinkedHashMap<>();
    for (int i = 2; i + 1 < a.length; i += 2) sets.put(a[i], Arrays.asList(Files.readString(Path.of(a[i + 1])).trim().split("\n")));
    // writer device starts a jam after everyone has registered
    HttpClient wh = client(); WebSocket writer = open(wh, port, new AtomicLong(), new AtomicLong(), null);
    Map<String, AtomicLong[]> stats = new LinkedHashMap<>(); List<WebSocket> socks = new ArrayList<>();
    for (Map.Entry<String, List<String>> e : sets.entrySet()) {
      AtomicLong b = new AtomicLong(), f = new AtomicLong(); WebSocket ws = open(client(), port, b, f, null);
      ws.sendText(json(e.getValue()), true).get(); stats.put(e.getKey(), new AtomicLong[]{b, f}); socks.add(ws);
    }
    Thread.sleep(3000);
    Map<String, long[]> initial = new LinkedHashMap<>(); stats.forEach((k, v) -> initial.put(k, new long[]{v[0].get(), v[1].get()}));
    writer.sendText("{\"action\":\"Set\",\"key\":\"ScoreBoard.Game(" + game + ").StartJam\",\"value\":true}", true).get();
    Thread.sleep(10000);
    System.out.printf("%-26s %8s %14s %14s%n", "subscription", "paths", "initial load", "10 s of a jam");
    stats.forEach((k, v) -> System.out.printf("%-26s %8d %9d KB %5d fr %8d KB %5d fr%n", k, sets.get(k).size(), initial.get(k)[0] / 1024, initial.get(k)[1], (v[0].get() - initial.get(k)[0]) / 1024, v[1].get() - initial.get(k)[1]));
    System.exit(0);
  }
}
