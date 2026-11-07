// The same Python Spider protocol runs over the device-local JS engine on each
// host. A suspended HTTP request becomes a Promise only on asynchronous hosts.
function createPythonSpider(source, files = {}, readSibling) {
  const baseSpider = [
    "import requests",
    "class Spider:",
    "    def init(self, extend=''): pass",
    "    def fetch(self, url, **kwargs): return requests.get(url, **kwargs)",
    "    def post(self, url, **kwargs): return requests.post(url, **kwargs)",
  ].join("\n");
  const requestsModule = [
    "import _movie_http, json",
    "class Response:",
    "    def __init__(self, r):",
    "        self.status_code = r['code']",
    "        self.text = r['content']",
    "        self.content = self.text.encode('utf-8')",
    "        self.headers = r.get('headers', {})",
    "    def json(self): return json.loads(self.text)",
    "    def raise_for_status(self):",
    "        if self.status_code >= 400: raise Exception('HTTP ' + str(self.status_code))",
    "def get(url, headers=None, **kwargs):",
    "    url = _movie_http.with_params(url, kwargs.get('params', {}))",
    "    return Response(_movie_http.request(url, {'method':'GET','headers':headers or {}}))",
    "def post(url, data=None, json=None, headers=None, **kwargs):",
    "    url = _movie_http.with_params(url, kwargs.get('params', {}))",
    "    headers = dict(headers or {})",
    "    if json is not None:",
    "        headers['Content-Type'] = 'application/json'",
    "        data = _movie_http.json_data(json)",
    "    elif isinstance(data, dict):",
    "        headers['Content-Type'] = 'application/x-www-form-urlencoded'",
    "        data = _movie_http.form_data(data)",
    "    return Response(_movie_http.request(url, {'method':'POST','headers':headers,'data':data}))",
  ].join("\n");
  const httpBridge = `var $builtinmodule = function() {
    function query(p) {
      return Object.keys(p).map(k => encodeURIComponent(k) + '=' + encodeURIComponent(p[k])).join('&');
    }
    return {
      with_params: new Sk.builtin.func(function(url, params) {
        const value = Sk.ffi.remapToJs(url), q = query(Sk.ffi.remapToJs(params) || {});
        return new Sk.builtin.str(value + (q ? (value.indexOf('?') < 0 ? '?' : '&') + q : ''));
      }),
      form_data: new Sk.builtin.func(function(data) {
        return new Sk.builtin.str(query(Sk.ffi.remapToJs(data)));
      }),
      json_data: new Sk.builtin.func(function(data) {
        return new Sk.builtin.str(JSON.stringify(Sk.ffi.remapToJs(data)));
      }),
      request: new Sk.builtin.func(function(url, opts) {
      const r = req(Sk.ffi.remapToJs(url), Sk.ffi.remapToJs(opts));
      if (r && typeof r.then === "function") {
        return Sk.misceval.promiseToSuspension(r.then(v => Sk.ffi.remapToPy(v)));
      }
      return Sk.ffi.remapToPy(r);
    })};
  };`;
  const library = Object.assign(Object.create(null), Sk.builtinFiles.files);
  library["src/lib/json/__init__.py"] = [
    "import _movie_json",
    "def loads(s, **kwargs): return _movie_json.loads(s)",
    "def dumps(obj, ensure_ascii=True, **kwargs): return _movie_json.dumps(obj)",
  ].join("\n");
  library["src/lib/_movie_json.js"] = `var $builtinmodule = function() {
    return {
      loads: new Sk.builtin.func(function(s) {
        return Sk.ffi.remapToPy(JSON.parse(Sk.ffi.remapToJs(s)));
      }),
      dumps: new Sk.builtin.func(function(obj) {
        return new Sk.builtin.str(JSON.stringify(Sk.ffi.remapToJs(obj)));
      })
    };
  };`;
  library["src/lib/base/__init__.py"] = "";
  library["src/lib/base/spider.py"] = baseSpider;
  library["src/lib/requests.py"] = requestsModule;
  library["src/lib/_movie_http.js"] = httpBridge;
  for (const path of Object.keys(files)) {
    if (/^(?:[A-Za-z_]\w*\/)*[A-Za-z_]\w*\.py$/.test(path)) {
      library["src/lib/" + path] = files[path];
    }
  }
  Sk.configure({
    __future__: Sk.python3,
    execLimit: 8000,
    output() {},
    read(path) {
      if (Object.prototype.hasOwnProperty.call(library, path)) return library[path];
      if (readSibling && /^src\/lib\/(?:[A-Za-z_]\w*\/)*[A-Za-z_]\w*\.py$/.test(path)) {
        const text = readSibling(path.substring("src/lib/".length));
        if (typeof text === "string") return text;
      }
      throw new Error("Python 模块不可用：" + path);
    }
  });
  let instance;
  const py = Sk.ffi.remapToPy;
  function run(call) {
    const value = call();
    if (value && value.$isSuspension) {
      return Sk.misceval.asyncToPromise(() => value).then(v => Sk.ffi.remapToJs(v));
    }
    return Sk.ffi.remapToJs(value);
  }
  function invoke(name, args, optional) {
    const fn = instance.tp$getattr(new Sk.builtin.str(name));
    if (!fn && optional) return {};
    if (!fn) throw new Error("Python Spider 缺少 " + name);
    return run(() => Sk.misceval.callsimOrSuspendArray(fn, args.map(x => py(x))));
  }
  return {
    init(ext) {
      const boot = Sk.importMainWithBody("<source>", false, source, true);
      function initialize(mod) {
        if (!mod.$d.Spider) throw new Error("Python 来源缺少 Spider 类");
        instance = Sk.misceval.callsimArray(mod.$d.Spider, []);
        return invoke("init", [ext], true);
      }
      return boot && boot.$isSuspension
        ? Sk.misceval.asyncToPromise(() => boot).then(initialize) : initialize(boot);
    },
    home(filter) { return invoke("homeContent", [filter]); },
    homeVod() { return invoke("homeVideoContent", [], true); },
    category(id, page, filter, ext) {
      return invoke("categoryContent", [id, String(page), filter, ext]);
    },
    detail(id) { return invoke("detailContent", [[id]]); },
    search(word, quick, page) {
      const fn = instance.tp$getattr(new Sk.builtin.str("searchContent"));
      const argc = fn && fn.im_func && fn.im_func.co_argcount;
      return invoke("searchContent", argc >= 4 ? [word, quick, String(page)] : [word, quick]);
    },
    play(flag, id, vip) { return invoke("playerContent", [flag, id, vip]); },
  };
}
