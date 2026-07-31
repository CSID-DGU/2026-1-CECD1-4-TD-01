#pragma once

#include <Arduino.h>

const char kConfigUi[] PROGMEM = R"HTML(
<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
  <meta name="theme-color" content="#181818">
  <title>IoT Cam Bridge</title>
  <style>
    :root{--bg:#181818;--side:#202020;--panel:#252526;--line:#343434;--text:#d4d4d4;--muted:#919191;--blue:#007acc;--blue2:#1687d9;--green:#4ec9b0;--yellow:#dcdcaa;--red:#f14c4c;--input:#1e1e1e;--shadow:0 12px 35px #0006}
    *{box-sizing:border-box}html,body{margin:0;min-height:100%;background:var(--bg);color:var(--text);font:14px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif}button,input,textarea{font:inherit}
    body{display:grid;grid-template-columns:48px 250px minmax(0,1fr);grid-template-rows:35px minmax(calc(100vh - 57px),auto) 22px;grid-template-areas:"title title title" "rail side main" "status status status";overflow-x:hidden}
    .title{grid-area:title;background:#323233;display:flex;align-items:center;padding:0 12px;border-bottom:1px solid #111;gap:12px;position:sticky;top:0;z-index:10}.title b{font-weight:500}.title small{color:var(--muted)}
    .rail{grid-area:rail;background:#2b2b2b;border-right:1px solid #111;display:flex;flex-direction:column;align-items:center;padding-top:10px}.rail .icon{width:48px;height:48px;display:grid;place-items:center;color:#aaa;border-left:2px solid transparent;font:600 18px ui-monospace,monospace}.rail .icon.active{color:#fff;border-left-color:#fff;background:#ffffff0a}
    .side{grid-area:side;background:var(--side);border-right:1px solid #111;padding:14px 12px;position:sticky;top:35px;height:calc(100vh - 57px);overflow:auto}.eyebrow{font-size:11px;letter-spacing:.12em;color:#aaa;text-transform:uppercase;margin:2px 8px 15px}.tree{list-style:none;margin:0;padding:0}.tree li{padding:7px 9px;border-radius:4px;color:#c9c9c9;cursor:pointer}.tree li:hover,.tree li.active{background:#37373d;color:#fff}.tree li::before{content:"?";display:inline-block;margin-right:8px;color:#999}.side-card{margin-top:22px;border:1px solid var(--line);background:#1b1b1b;padding:12px;border-radius:6px}.side-card strong{display:block;margin-bottom:8px}.kv{display:grid;grid-template-columns:1fr auto;gap:5px;color:var(--muted);font-size:12px}.kv span:nth-child(even){color:#ccc;text-align:right;max-width:130px;overflow:hidden;text-overflow:ellipsis}
    main{grid-area:main;min-width:0;padding:20px 24px 45px}.tabs{height:36px;margin:-20px -24px 18px;display:flex;align-items:end;background:#1f1f1f;border-bottom:1px solid #111}.tab{height:36px;padding:9px 18px;background:var(--panel);border-right:1px solid #111;border-top:1px solid var(--blue);font:12px ui-monospace,monospace;color:#eee}
    .hero{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:18px}.hero h1{font-size:22px;font-weight:500;margin:0 0 4px}.hero p{color:var(--muted);margin:0}.badge{display:inline-flex;align-items:center;gap:7px;border:1px solid var(--line);background:#222;padding:7px 11px;border-radius:999px;white-space:nowrap}.dot{width:8px;height:8px;border-radius:50%;background:var(--red);box-shadow:0 0 0 3px #f14c4c22}.dot.ok{background:var(--green);box-shadow:0 0 0 3px #4ec9b022}.dot.warn{background:#cca700;box-shadow:0 0 0 3px #cca70022}
    .grid{display:grid;grid-template-columns:minmax(0,1.35fr) minmax(280px,.65fr);gap:16px}.panel{background:var(--panel);border:1px solid var(--line);border-radius:7px;box-shadow:var(--shadow);overflow:hidden}.panel-head{display:flex;align-items:center;justify-content:space-between;padding:11px 14px;border-bottom:1px solid var(--line);background:#2b2b2b}.panel-head h2{font-size:13px;font-weight:600;margin:0}.panel-body{padding:16px}
    label{display:block;margin:0 0 14px;color:#c8c8c8;font-size:12px}label span{display:block;margin-bottom:6px}input,textarea{width:100%;border:1px solid #3f3f46;background:var(--input);color:#e7e7e7;border-radius:3px;padding:9px 10px;outline:none}input:focus,textarea:focus{border-color:var(--blue)}input::placeholder{color:#666}.inline{display:grid;grid-template-columns:1fr 120px;gap:10px}.hint{font-size:11px;color:var(--muted);margin-top:-7px;margin-bottom:14px}
    .actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:4px}button{border:1px solid #505050;background:#333;color:#eee;padding:8px 12px;border-radius:3px;cursor:pointer}button:hover{background:#414141}button.primary{border-color:var(--blue);background:var(--blue)}button.primary:hover{background:var(--blue2)}button.danger{color:#ffb3b3;border-color:#6b3030}button:disabled{opacity:.45;cursor:wait}
    .status-grid{display:grid;grid-template-columns:1fr 1fr;gap:9px}.metric{border:1px solid var(--line);background:#1d1d1d;padding:11px;border-radius:5px}.metric small{display:block;color:var(--muted);margin-bottom:4px}.metric strong,.metric a{font:600 14px ui-monospace,monospace;word-break:break-all}.metric.wide{grid-column:1/-1}.device-link{color:#75beff;text-decoration:none}.device-link:hover{text-decoration:underline}.device-link.offline{color:var(--muted);pointer-events:none}.connection-tip{grid-column:1/-1;border-left:3px solid var(--blue);background:#16232d;color:#c7dff1;padding:10px 11px;border-radius:3px;font-size:12px}.connection-tip b{color:#fff}
    .networks{display:grid;gap:6px;max-height:250px;overflow:auto}.network{display:grid;grid-template-columns:1fr auto auto;align-items:center;gap:9px;border:1px solid var(--line);padding:9px 10px;background:#1d1d1d;border-radius:4px;cursor:pointer}.network:hover{border-color:#666}.network b{font-weight:500;overflow:hidden;text-overflow:ellipsis}.network small{color:var(--muted)}
    .editor{margin-top:16px}.editor textarea{min-height:190px;resize:vertical;color:#ce9178;font:13px/1.55 ui-monospace,"Cascadia Code",Consolas,monospace;tab-size:2}.console{margin-top:16px;background:#0f0f0f;border:1px solid #333;border-radius:5px;min-height:115px;max-height:180px;overflow:auto;padding:10px;font:12px/1.5 ui-monospace,monospace;color:#b5cea8}.console div::before{content:"> ";color:#569cd6}
    .statusbar{grid-area:status;background:#007acc;color:white;display:flex;align-items:center;justify-content:space-between;padding:0 10px;font-size:11px;position:fixed;left:0;right:0;bottom:0;height:22px;z-index:12}.toast{position:fixed;right:18px;bottom:38px;background:#252526;border:1px solid #555;padding:12px 15px;border-radius:5px;box-shadow:var(--shadow);transform:translateY(120px);opacity:0;transition:.25s;z-index:30}.toast.show{transform:none;opacity:1}.toast.error{border-color:var(--red)}
    @media(max-width:850px){body{grid-template-columns:minmax(0,1fr);grid-template-areas:"title" "main" "status"}.rail,.side{display:none}main{padding:16px 14px 42px}.tabs{margin:-16px -14px 16px}.grid{grid-template-columns:minmax(0,1fr)}.panel{min-width:0}.hero{align-items:stretch;flex-direction:column}.badge{align-self:flex-start}.inline{grid-template-columns:minmax(0,1fr) 95px}.inline>*{min-width:0}.inline input{min-width:0}.panel-head{gap:8px;min-width:0;overflow:hidden}.panel-head h2{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.panel-head button{white-space:nowrap}.statusbar span:last-child{display:none}}
  </style>
</head>
<body>
  <header class="title"><b>IoT Cam Bridge</b><small>ESP8266/ESP8285 configuration workspace</small></header>
  <nav class="rail"><div class="icon active">C</div><div class="icon">W</div><div class="icon">J</div></nav>
  <aside class="side">
    <div class="eyebrow">Explorer</div>
    <ul class="tree"><li class="active">bridge.config.json</li><li>connection.status</li><li>uart.transport</li><li>system.health</li></ul>
    <div class="side-card"><strong>DEVICE</strong><div class="kv"><span>Hostname</span><span id="sideHost">?</span><span>Firmware</span><span>bridge-2</span><span>Mode</span><span id="sideMode">?</span><span>Free heap</span><span id="sideHeap">?</span></div></div>
  </aside>
  <main>
    <div class="tabs"><div class="tab">bridge.config.json&nbsp; ×</div></div>
    <section class="hero"><div><h1>카메라 &amp; Jetson 연결</h1><p>핫스팟 연결 후에도 같은 Wi-Fi의 장치 주소로 이 화면을 다시 열 수 있습니다.</p></div><div class="badge"><span id="topDot" class="dot"></span><span id="topState">상태 확인 중</span></div></section>
    <div class="grid">
      <section class="panel"><div class="panel-head"><h2>CONNECTION SETTINGS</h2><button id="scanBtn" onclick="scanNetworks()">Wi-Fi 검색</button></div><div class="panel-body">
        <label><span>2.4GHz Wi-Fi SSID</span><input id="ssid" maxlength="32" autocomplete="off" placeholder="휴대전화 핫스팟 이름 또는 공유기 이름"></label>
        <label><span>Wi-Fi 비밀번호</span><input id="password" type="password" maxlength="63" autocomplete="new-password" placeholder="변경할 때만 비밀번호 입력"></label>
        <div class="hint" id="passwordHint">저장된 비밀번호는 보안을 위해 화면에 표시하지 않습니다.</div>
        <div class="inline"><label><span>Jetson 호스트</span><input id="host" maxlength="63" placeholder="예: 192.168.45.120"></label><label><span>TCP 포트</span><input id="port" type="number" min="1" max="65535" value="9000"></label></div>
        <div class="actions"><button class="primary" onclick="saveConfig()">저장 후 재연결</button><button onclick="restartDevice()">ESP 재시작</button><button class="danger" onclick="factoryReset()">설정 초기화</button></div>
      </div></section>
      <section class="panel"><div class="panel-head"><h2>LIVE STATUS</h2><span id="updatedAt" style="font-size:11px;color:#999">-</span></div><div class="panel-body"><div class="status-grid">
        <div class="metric"><small>Wi-Fi</small><strong id="wifiState">-</strong></div><div class="metric"><small>Jetson TCP</small><strong id="tcpState">-</strong></div>
        <div class="metric wide"><small>같은 Wi-Fi 접속 주소</small><a id="deviceUrl" class="device-link offline" href="#">Wi-Fi 연결 후 표시됩니다</a></div>
        <div class="metric wide"><small>고정 이름 (지원되는 기기)</small><a class="device-link" href="http://iotcam-bridge.local/" target="_blank" rel="noopener">http://iotcam-bridge.local/</a></div>
        <div class="connection-tip" id="connectionTip"><b>전환 방법:</b> 주소가 표시되면 복사 → 휴대전화를 설정 AP가 아닌 핫스팟에 다시 연결 → 표시된 주소를 여세요.</div>
        <div class="metric"><small>RSSI</small><strong id="rssi">-</strong></div><div class="metric"><small>UART RX</small><strong id="uartRx">-</strong></div>
        <div class="metric"><small>전송됨</small><strong id="forwarded">-</strong></div><div class="metric"><small>버림</small><strong id="dropped">-</strong></div>
        <div class="metric wide"><small>설정 AP</small><strong id="setupAp">-</strong></div>
      </div></div></section>
    </div>
    <section class="panel" style="margin-top:16px"><div class="panel-head"><h2>AVAILABLE 2.4GHz NETWORKS</h2><small id="scanState" style="color:#999">검색 버튼을 누르세요</small></div><div class="panel-body"><div id="networks" class="networks"><div style="color:#777">주변 네트워크를 아직 검색하지 않았습니다.</div></div></div></section>
    <section class="panel editor"><div class="panel-head"><h2>ADVANCED · JSON EDITOR</h2><button onclick="applyJson()">JSON을 위 설정에 적용</button></div><div class="panel-body"><textarea id="jsonEditor" spellcheck="false"></textarea><div class="hint" style="margin:8px 0 0">password 값은 보안상 실제 저장 값이 JSON에 표시되지 않습니다.</div></div></section>
    <div id="console" class="console"><div>Configuration workspace ready.</div></div>
  </main>
  <footer class="statusbar"><span id="footerLeft">IoT Cam Bridge</span><span>UART 1,000,000 · P4V1 → TCP</span></footer>
  <div id="toast" class="toast"></div>
  <script>
    const $=id=>document.getElementById(id);let cfg={},lastStatus={};
    function log(msg){const d=document.createElement('div');d.textContent=new Date().toLocaleTimeString()+'  '+msg;$('console').appendChild(d);$('console').scrollTop=$('console').scrollHeight}
    function toast(msg,error=false){const e=$('toast');e.textContent=msg;e.className='toast show'+(error?' error':'');setTimeout(()=>e.className='toast',2600)}
    function fmt(n){if(n==null)return '?';if(n<1024)return n+' B';if(n<1048576)return (n/1024).toFixed(1)+' KB';return (n/1048576).toFixed(2)+' MB'}
    async function request(url,opt){const r=await fetch(url,opt);const j=await r.json();if(!r.ok)throw new Error(j.error||('HTTP '+r.status));return j}
    function syncJson(){const v={wifi:{ssid:$('ssid').value,password:'<unchanged>'},jetson:{host:$('host').value,port:Number($('port').value||9000)},transport:{uart_baud:1000000,tcp:true}};$('jsonEditor').value=JSON.stringify(v,null,2)}
    function applyJson(){try{const v=JSON.parse($('jsonEditor').value);if(v.wifi?.ssid!=null)$('ssid').value=v.wifi.ssid;if(v.jetson?.host!=null)$('host').value=v.jetson.host;if(v.jetson?.port!=null)$('port').value=v.jetson.port;syncJson();toast('JSON 설정을 위에 적용했습니다');log('JSON editor applied')}catch(e){toast('JSON 오류: '+e.message,true)}}
    async function loadConfig(){try{cfg=await request('/api/config');$('ssid').value=cfg.ssid||'';$('host').value=cfg.jetson_host||'';$('port').value=cfg.jetson_port||9000;$('passwordHint').textContent=cfg.password_set?'비밀번호가 저장되어 있습니다. 바꿀 때만 입력하세요.':'아직 비밀번호가 없습니다.';$('sideHost').textContent=cfg.device_hostname||'-';syncJson();log('Stored configuration loaded')}catch(e){log('Config load failed: '+e.message)}}
    function setBool(id,value,yes='연결됨',no='끊김'){$(id).textContent=value?yes:no;$(id).style.color=value?'var(--green)':'var(--red)'}
    async function poll(){try{const s=await request('/api/status');const wasConnected=lastStatus.wifi_connected===true;setBool('wifiState',s.wifi_connected);setBool('tcpState',s.tcp_connected);const stationUrl=s.wifi_connected&&s.ip?'http://'+s.ip+'/':'';const link=$('deviceUrl');link.textContent=stationUrl||'Wi-Fi 연결 후 표시됩니다';link.href=stationUrl||'#';link.className='device-link'+(stationUrl?'':' offline');$('connectionTip').textContent=stationUrl?'다음 접속: '+stationUrl+' 을 기억한 뒤 휴대전화를 '+String(s.wifi_ssid||'같은 Wi-Fi')+'에 연결하고 이 주소를 여세요.':'전환 방법: 주소가 표시되면 복사 → 휴대전화를 설정 AP가 아닌 핫스팟에 다시 연결 → 표시된 주소를 여세요.';$('rssi').textContent=s.wifi_connected?s.rssi_dbm+' dBm':'-';$('uartRx').textContent=fmt(s.uart_bytes_received);$('forwarded').textContent=fmt(s.wifi_bytes_forwarded);$('dropped').textContent=fmt(s.dropped_bytes);$('setupAp').textContent=s.ap_active?(s.ap_ssid+' · 192.168.4.1'):'꺼짐';$('sideMode').textContent=s.ap_active?'SETUP AP':'STATION';$('sideHeap').textContent=fmt(s.free_heap);$('updatedAt').textContent=new Date().toLocaleTimeString();$('footerLeft').textContent=(s.ip||'offline')+'  ·  '+(s.wifi_ssid||'no network');const ok=s.wifi_connected&&s.tcp_connected;$('topDot').className='dot '+(ok?'ok':s.wifi_connected?'warn':'');$('topState').textContent=ok?'카메라 경로 정상':s.wifi_connected?'Jetson 연결 대기':'Wi-Fi 설정 필요';if(s.wifi_connected&&!wasConnected&&lastStatus.wifi_connected!==undefined){toast('핫스팟 연결 성공 · 다음 주소: '+stationUrl);log('Same-Wi-Fi portal: '+stationUrl)}lastStatus=s}catch(e){$('topState').textContent='장치 응답 없음';$('topDot').className='dot'}}
    async function scanNetworks(){const b=$('scanBtn');b.disabled=true;$('scanState').textContent='검색 중';log('Scanning nearby 2.4GHz networks');try{const j=await request('/api/scan');const box=$('networks');box.innerHTML='';j.networks.forEach(n=>{const e=document.createElement('div');e.className='network';e.innerHTML='<b></b><small></small><span></span>';e.children[0].textContent=n.ssid||'(숨김)';e.children[1].textContent=n.rssi+' dBm · CH '+n.channel;e.children[2].textContent=n.secure?'잠금':'열림';e.onclick=()=>{$('ssid').value=n.ssid;syncJson();toast('SSID를 선택했습니다')};box.appendChild(e)});if(!j.networks.length)box.textContent='검색된 네트워크가 없습니다.';$('scanState').textContent=j.networks.length+'개 발견';log('Scan complete: '+j.networks.length+' network(s)')}catch(e){toast('검색 실패: '+e.message,true);$('scanState').textContent='검색 실패'}finally{b.disabled=false}}
    async function saveConfig(){const data=new URLSearchParams({ssid:$('ssid').value.trim(),password:$('password').value,jetson_host:$('host').value.trim(),jetson_port:$('port').value});try{await request('/api/save',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:data});toast('저장했습니다. ESP가 재연결됩니다.');log('Configuration saved; reboot scheduled')}catch(e){toast('저장 실패: '+e.message,true)}}
    async function restartDevice(){if(!confirm('ESP를 재시작할까요?'))return;try{await request('/api/restart',{method:'POST'});toast('재시작합니다');log('Restart requested')}catch(e){toast(e.message,true)}}
    async function factoryReset(){if(!confirm('저장된 Wi-Fi와 Jetson 설정을 모두 지울까요?'))return;try{await request('/api/reset',{method:'POST'});toast('설정을 지우고 재시작합니다');log('Factory reset requested')}catch(e){toast(e.message,true)}}
    ['ssid','host','port'].forEach(id=>$(id).addEventListener('input',syncJson));loadConfig();poll();setInterval(poll,2000);
  </script>
</body>
</html>
)HTML";
