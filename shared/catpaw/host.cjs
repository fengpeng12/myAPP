// Copyright (C) 2026 Pilipala. SPDX-License-Identifier: GPL-3.0-only
// App-owned CatPaw host. Imported bundles are user code, never bundled with the app.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const crypto = require('node:crypto');
const { Worker, isMainThread, parentPort, workerData } = require('node:worker_threads');
const HASH = /^[a-f0-9]{64}$/;

if (!isMainThread) {
  // Node 18's built-in fetch initializes a WASM HTTP parser even under --jitless.
  // Use a pinned, pure-JS Fetch implementation on iOS; preserve the standard API.
  if (typeof WebAssembly === 'undefined') {
    // Node 22 lazily initializes Undici even when its global accessor is
    // redefined. Delete those accessors before installing the no-WASM shim.
    for (const name of ['Headers', 'Request', 'Response', 'FormData', 'Blob', 'File', 'fetch']) {
      delete globalThis[name];
    }
    const fetch = require('./fetch.cjs');
    for (const name of ['Headers', 'Request', 'Response', 'FormData', 'Blob', 'File']) {
      Object.defineProperty(globalThis, name, { configurable: true, writable: true, value: fetch[name] });
    }
    Object.defineProperty(globalThis, 'fetch', { configurable: true, writable: true, value: fetch.default });
  }
  // A separate Node isolate gives each subscription its own globals and module cache.
  const net = require('node:net');
  // Bundles must advertise this device's loopback listener, not a LAN address.
  require('node:os').networkInterfaces = () => ({ lo: [{ address: '127.0.0.1',
    netmask: '255.0.0.0', family: 'IPv4', mac: '00:00:00:00:00:00', internal: true,
    cidr: '127.0.0.1/8' }] });
  const listen = net.Server.prototype.listen;
  net.Server.prototype.listen = function (...args) {
    const callback = args.find(v => typeof v === 'function');
    return listen.call(this, { host: '127.0.0.1', port: 0 }, callback);
  };
  process.env.NODE_PATH = workerData.state;
  let main;
  global.catServerFactory = handler => (main = http.createServer(handler));
  global.catDartServerPort = () => 0;
  global.messageToDart = async () => null;
  (async () => {
    const bundle = require(workerData.script);
    if (typeof bundle.start !== 'function') throw Error('entry');
    await bundle.start({});
    if (main && !main.listening) await new Promise((resolve, reject) => {
      main.once('listening', resolve); main.once('error', reject);
    });
    const endpoint = main?.address();
    if (!endpoint || endpoint.address !== '127.0.0.1') throw Error('listen');
    parentPort.postMessage({ port: endpoint.port });
  })().catch(() => { parentPort.postMessage({ error: 'bundle_start_failed' }); });
} else {
  const root = path.resolve(process.argv[2]);
  const readyFile = path.resolve(process.argv[3]);
  const secret = crypto.randomBytes(32).toString('hex');
  const runtimes = new Map();
  let closing = false;
  fs.mkdirSync(root, { recursive: true, mode: 0o700 });
  const respond = (res, status, body) => {
    res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(body));
  };
  async function load(hash) {
    if (!HASH.test(hash)) throw Error('bundle_id');
    if (runtimes.has(hash)) return runtimes.get(hash).ready;
    // Do not evict an active playback to make room for a background source search.
    if (runtimes.size >= 3) {
      const idle = [...runtimes.entries()].filter(([, r]) => r.port && !r.active && !r.manager &&
        Date.now() - r.played > 15 * 60 * 1000).sort((a,b) => a[1].used - b[1].used)[0];
      if (!idle) throw Error('runtime_limit');
      runtimes.delete(idle[0]); await idle[1].worker.terminate();
    }
    const folder = path.join(root, hash);
    const script = path.join(folder, 'index.cjs');
    const state = path.join(folder, 'state');
    const stat = fs.statSync(script);
    if (!stat.isFile() || stat.size > 16 * 1024 * 1024) throw Error('bundle_size');
    const bytes = fs.readFileSync(script);
    if (crypto.createHash('sha256').update(bytes).digest('hex') !== hash) throw Error('bundle_changed');
    fs.mkdirSync(state, { recursive: true, mode: 0o700 });
    const worker = new Worker(__filename, {
      workerData: { script, state }, stdout: true, stderr: true,
      resourceLimits: { maxOldGenerationSizeMb: 256 },
    });
    // Source logs can contain cookies, query strings and media tokens.
    worker.stdout.resume(); worker.stderr.resume();
    const instance = { worker, ready: null, port: 0, active: 0, used: Date.now(), played: 0, manager: null };
    instance.ready = new Promise((resolve, reject) => {
      const timer = setTimeout(() => { worker.terminate(); reject(Error('bundle_timeout')); }, 25000);
      worker.once('message', value => {
        clearTimeout(timer);
        if (Number.isInteger(value.port) && value.port > 0 && value.port < 65536) {
          instance.port = value.port; resolve(instance);
        } else { worker.terminate(); reject(Error('bundle_start_failed')); }
      });
      worker.once('error', () => { clearTimeout(timer); reject(Error('bundle_start_failed')); });
      worker.once('exit', () => {
        instance.manager?.close();
        clearTimeout(timer);
        if (runtimes.get(hash) === instance) runtimes.delete(hash);
        reject(Error('bundle_exited'));
      });
    });
    runtimes.set(hash, instance);
    return instance.ready;
  }

  // A separate loopback origin preserves the source author's settings UI paths.
  // Only an explicit app request creates it; a one-time ticket establishes a
  // per-origin HttpOnly session. The playback endpoint never exposes admin paths.
  async function manage(instance) {
    if (instance.manager) return instance.manager.open();
    const cookie = 'marco_' + crypto.randomBytes(12).toString('hex');
    const value = crypto.randomBytes(32).toString('hex');
    let ticket = '', expires = Date.now() + 15 * 60 * 1000;
    const backend = `http://127.0.0.1:${instance.port}`;
    const manager = http.createServer((req, res) => {
      const origin = `http://127.0.0.1:${manager.address().port}`;
      if (req.headers.host !== `127.0.0.1:${manager.address().port}` ||
          (req.headers.origin && req.headers.origin !== origin)) return respond(res,403,{error:'forbidden'});
      res.setHeader('Referrer-Policy','no-referrer');
      res.setHeader('X-Frame-Options','DENY');
      res.setHeader('Cache-Control','no-store');
      if (ticket && req.method === 'GET' && req.url === '/authorize/' + ticket) {
        ticket = '';
        res.writeHead(303,{Location:'/website', 'Set-Cookie': `${cookie}=${value}; Path=/; HttpOnly; SameSite=Strict`});
        res.end(); return;
      }
      if (Date.now() > expires || !(req.headers.cookie || '').split(';').some(v=>v.trim()===`${cookie}=${value}`)) {
        return respond(res,403,{error:'settings_session_expired'});
      }
      expires = Date.now() + 15 * 60 * 1000;
      if (!req.url.startsWith('/') || req.url.startsWith('//')) return respond(res,400,{error:'path'});
      const headers = {...req.headers,host:`127.0.0.1:${instance.port}`};
      delete headers.cookie; delete headers.origin; delete headers['accept-encoding'];
      const upstream = http.request(backend+req.url,{method:req.method,headers},source=>{
        const output={...source.headers};
        delete output['set-cookie']; delete output['access-control-allow-origin'];
        const rewrite=text=>text.split(backend).join(origin);
        if(output.location) output.location=rewrite(output.location);
        const transformed=/json|text\/|javascript/i.test(String(output['content-type']||''));
        if(!transformed) {res.writeHead(source.statusCode,output);source.pipe(res);}
        else {
          delete output['content-length'];delete output['content-encoding'];
          let size=0;const chunks=[];
          source.on('data',chunk=>{size+=chunk.length;if(size>24*1024*1024){source.destroy();res.destroy();}else chunks.push(chunk);});
          source.on('end',()=>{if(!res.destroyed){res.writeHead(source.statusCode,output);res.end(rewrite(Buffer.concat(chunks).toString('utf8')));}});
        }
        source.on('error',()=>res.destroy());
      });
      upstream.setTimeout(30000,()=>upstream.destroy());
      upstream.on('error',()=>{if(!res.headersSent)respond(res,502,{error:'settings_request_failed'});else res.destroy();});
      res.on('close',()=>upstream.destroy());req.pipe(upstream);
    });
    await new Promise((resolve,reject)=>{manager.once('error',reject);manager.listen(0,'127.0.0.1',resolve);});
    const expiry = setInterval(()=>{if(Date.now()>expires) session.close();},60000);
    expiry.unref();
    const session = instance.manager = {
      open() {ticket=crypto.randomBytes(32).toString('hex');expires=Date.now()+15*60*1000;
        return `http://127.0.0.1:${manager.address().port}/authorize/${ticket}`;},
      close() {clearInterval(expiry);manager.close();manager.closeAllConnections();instance.manager=null;},
    };
    return session.open();
  }

  const server = http.createServer(async (req, res) => {
    if (closing || req.headers.origin || !req.url.startsWith('/' + secret + '/')) {
      return respond(res, 403, { error: 'forbidden' });
    }
    const route = req.url.slice(secret.length + 2);
    if (route === 'health' && req.method === 'GET') return respond(res, 200, { version: 1 });
    const match = /^([a-f0-9]{64})(\/.*)$/.exec(route);
    if (!match) return respond(res, 404, { error: 'route' });
    const hash = match[1], resource = match[2];
    if (resource === '/_release' && req.method === 'POST') {
      const instance = runtimes.get(hash);
      if (instance) { runtimes.delete(hash); await instance.worker.terminate(); }
      return respond(res, 200, { released: true });
    }
    if (resource === '/_manage' && req.method === 'POST') {
      try { return respond(res,200,{url:await manage(await load(hash))}); }
      catch (_) { return respond(res,503,{error:'settings_unavailable'}); }
    }
    // Only data/playback routes are exposed to the player, never the source's admin panel.
    if (!/^\/(t4(?:\?|$)|api\/|proxy(?:\/|\?)|videos\/|stream\.)/.test(resource)) {
      return respond(res, 403, { error: 'route_not_exposed' });
    }
    let instance;
    try { instance = await load(hash); }
    catch (_) { return respond(res, 503, { error: 'source_runtime_unavailable' }); }
    if (res.destroyed) return;
    instance.active++; instance.used=Date.now();
    if (/[?&]play=/.test(resource)) instance.played=Date.now();
    res.once('close',()=>{instance.active--;});
    const backend = `http://127.0.0.1:${instance.port}`;
    const publicBase = `http://127.0.0.1:${server.address().port}/${secret}/${hash}`;
    const rewrite = text => text.split(backend).join(publicBase)
      .split(`http://localhost:${instance.port}`).join(publicBase);
    const headers = { ...req.headers, host: `127.0.0.1:${instance.port}` };
    delete headers.authorization; delete headers.cookie; delete headers.origin;
    delete headers['accept-encoding'];
    const upstream = http.request(backend + resource, { method: req.method, headers }, source => {
      const type = String(source.headers['content-type'] || '');
      const transformed = /json|mpegurl|text\//i.test(type);
      const outputHeaders = { ...source.headers };
      delete outputHeaders['set-cookie']; delete outputHeaders['access-control-allow-origin'];
      if (outputHeaders.location) {
        // Relative redirects must remain under this subscription's capability path.
        const location = new URL(outputHeaders.location, backend + resource).href;
        outputHeaders.location = rewrite(location);
      }
      if (transformed) {
        delete outputHeaders['content-length']; delete outputHeaders['content-encoding'];
        let size = 0; const chunks = [];
        source.on('data', chunk => {
          size += chunk.length;
          if (size > 16 * 1024 * 1024) { source.destroy(); res.destroy(); }
          else chunks.push(chunk);
        });
        source.on('end', () => {
          if (res.destroyed) return;
          let body = rewrite(Buffer.concat(chunks).toString('utf8'));
          if (/mpegurl/i.test(type)) {
            const absolute = value => {
              if (!value || /^(data:|https?:)/i.test(value)) return rewrite(value);
              return rewrite(new URL(value, backend + resource).href);
            };
            body = body.split('\n').map(line => line.startsWith('#')
              ? line.replace(/URI="([^"]+)"/g, (_, value) => `URI="${absolute(value)}"`)
              : absolute(line.trim())).join('\n');
          }
          res.writeHead(source.statusCode, outputHeaders); res.end(body);
        });
        source.on('error', () => res.destroy());
      } else {
        res.writeHead(source.statusCode, outputHeaders); source.pipe(res);
        source.on('error', () => res.destroy());
      }
    });
    upstream.setTimeout(30000, () => upstream.destroy());
    upstream.on('error', () => {
      if (!res.headersSent) respond(res, 502, { error: 'source_request_failed' });
      else res.destroy();
    });
    res.on('close', () => upstream.destroy());
    req.pipe(upstream);
  });
  server.requestTimeout = 35000;
  server.headersTimeout = 10000;
  server.listen(0, '127.0.0.1', () => {
    const temporary = readyFile + '.tmp';
    fs.writeFileSync(temporary, JSON.stringify({ version: 1, port: server.address().port, secret }), { mode: 0o600 });
    fs.renameSync(temporary, readyFile);
  });
  const close = async () => {
    if (closing) return;
    closing = true;
    server.close(); server.closeAllConnections();
    await Promise.all([...runtimes.values()].map(r => r.worker.terminate()));
    try { fs.unlinkSync(readyFile); } catch (_) { }
    process.exit(0);
  };
  process.on('SIGTERM', close); process.on('SIGINT', close);
  if (!process.argv.includes('--embedded')) { process.stdin.resume(); process.stdin.on('end', close); }
}
