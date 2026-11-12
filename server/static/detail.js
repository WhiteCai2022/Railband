/* Local Leaflet assets; all uploaded text is rendered via textContent. */
'use strict';
(() => {
  const $ = (id) => document.getElementById(id);
  const text = (id, value) => { $(id).textContent = value; };
  const number = (value) => typeof value === 'number' && Number.isFinite(value);
  const count = (value) => number(value) ? value.toLocaleString('zh-CN') : '—';
  const rate = (value) => number(value) && value >= 0 ? `${(value / 1e6).toLocaleString('zh-CN', {maximumFractionDigits: 2})} Mbps` : '暂无数据';
  const timestamp = (value) => number(value) && value > 0 ? new Date(value).toLocaleString('zh-CN', {timeZone: 'Asia/Shanghai', hour12: false}) : '—';
  const duration = (value) => {
    if (!number(value) || value < 0) return '—';
    const seconds = Math.floor(value), h = Math.floor(seconds / 3600), m = Math.floor(seconds % 3600 / 60), s = seconds % 60;
    return `${h ? `${h} 小时 ` : ''}${m ? `${m} 分 ` : ''}${s} 秒`;
  };
  const states = {completed: '测量完成', cancelled: '手动停止', failed: '测量失败', timeout: '达到运行时限', interrupted: '意外中断', running: '测量中', unknown: '未知'};
  const networks = {cellular: '移动数据', wifi: 'Wi-Fi', vpn: 'VPN', none: '无网络', unknown: '未知', other: '其他网络'};
  const directions = {bidirectional: '上行和下行同时测量', phone_upload: '仅上行', phone_download: '仅下行'};
  const listText = (values, lookup) => Array.isArray(values) && values.length ? values.map((value) => lookup ? lookup[value] || value : value).join('、') : '未记录';
  function facts(target, rows) {
    target.replaceChildren();
    for (const [label, value] of rows) {
      const dt = document.createElement('dt'), dd = document.createElement('dd');
      dt.textContent = label; dd.textContent = value; target.append(dt, dd);
    }
  }
  function showNotice(id, message) { text(id, message); $(id).hidden = false; }
  async function start() {
    const response = await fetch($('detail').dataset.url, {credentials: 'same-origin', headers: {Accept: 'application/json'}});
    if (response.redirected) throw new Error('登录已失效，请刷新页面重新登录。');
    if (!response.ok) throw new Error(response.status === 404 ? '这份记录已删除或不存在。' : '暂时无法读取数据，请稍后刷新重试。');
    const data = await response.json(), summary = data.summary, track = data.track, item = data.item;
    $('detail-content').hidden = false; $('load-state').hidden = true;
    text('duration', duration(summary.duration_s)); text('status', states[summary.status] || summary.status || '未知');
    text('up-average', rate(summary.upload.average_bps)); text('up-peak', `峰值 ${rate(summary.upload.peak_bps)}`);
    text('down-average', rate(summary.download.average_bps)); text('down-peak', `峰值 ${rate(summary.download.peak_bps)}`);
    text('fix-count', count(track.fix_count));
    text('point-count', track.reduced ? `地图显示 ${count(track.display_point_count)} 个代表点` : `共 ${count(summary.sample_count)} 条速率记录`);
    if (item.kind === 'journey' && !item.complete) showNotice('completeness', `本次行程尚未完整接收，当前信息只基于已收到的 ${item.received_chunks} 个数据块${item.expected_chunks !== null ? `（应有 ${item.expected_chunks} 个）` : '，仍待结束块'}。补传完成后刷新可查看全程。`);
    facts($('measurement-facts'), [
      ['开始时间', `${timestamp(summary.started_utc_ms)}（北京时间）`], ['结束时间', timestamp(summary.ended_utc_ms)],
      ['设备', summary.device || item.device || '未知'], ['测量状态', states[summary.status] || summary.status || '未知'],
      ['测量方向', directions[summary.direction] || '未记录'], ['采样间隔', number(summary.sample_interval_s) ? `${summary.sample_interval_s} 秒` : '未记录'],
      ['限速设置', summary.rate_cap_bps === 0 ? '不限速' : rate(summary.rate_cap_bps)],
      ['速率记录', `${count(summary.sample_count)} 条${number(summary.interval_count) ? ` · ${count(summary.interval_count)} 个采样区间` : ''}`],
      ['独立观测', `${count(summary.telemetry_count)} 条`], ['网络类型', listText(summary.networks, networks)],
      ['场景标签', listText(summary.labels)], ['数据接收', item.kind === 'journey' ? `${item.received_chunks} 个数据块 · ${item.complete ? '已完整接收' : '尚未收齐'}` : '已完整接收'],
      ['测得上行时长', duration(summary.upload.seconds)], ['测得下行时长', duration(summary.download.seconds)]
    ]);
    if (Array.isArray(data.warnings)) for (const warning of data.warnings) {
      const p = document.createElement('p'); p.className = 'notice'; p.textContent = warning; $('warnings').append(p);
    }
    if (typeof L === 'undefined') throw new Error('地图组件未能加载，请刷新页面；原始数据仍可下载。');
    renderMap(track);
  }
  function renderMap(track) {
    const map = L.map('map', {preferCanvas: true, scrollWheelZoom: false, maxZoom: 19});
    L.control.scale({imperial: false}).addTo(map);
    const tiles = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors'});
    let tileErrors = 0, tileLoaded = false;
    tiles.on('tileerror', () => { tileErrors++; if (!tileLoaded || tileErrors > 2) showNotice('map-message', '底图暂时无法加载；定位轨迹仍可缩放、拖动和点选。'); });
    tiles.on('tileload', () => { tileLoaded = true; tileErrors = 0; $('map-message').hidden = true; });
    const points = [];
    for (const segment of track.segments || []) {
      const valid = segment.filter((p) => number(p.lat) && number(p.lon));
      if (!valid.length) continue;
      const startIndex = points.length; points.push(...valid);
      if (valid.length > 1) {
        const line = L.polyline(valid.map((p) => [p.lat, p.lon]), {color: '#14649a', weight: 4, opacity: .88}).addTo(map);
        line.on('click', (event) => {
          let index = startIndex, best = Infinity;
          valid.forEach((point, offset) => { const distance = map.latLngToLayerPoint([point.lat, point.lon]).distanceTo(map.latLngToLayerPoint(event.latlng)); if (distance < best) { best = distance; index = startIndex + offset; } });
          select(index, false);
        });
      } else L.circleMarker([valid[0].lat, valid[0].lon], {radius: 5, color: '#14649a', fillOpacity: .8}).on('click', () => select(startIndex, false)).addTo(map);
    }
    if (!points.length) {
      map.setView([35, 105], 4);
      showNotice('map-message', '这份测量没有有效定位数据，无法生成轨迹。后续测量请在 APP 中开启定位并授予权限。');
      $('fit-route').disabled = true; text('selected-time', '没有可查看的定位点'); return;
    }
    tiles.addTo(map);
    const bounds = L.latLngBounds(points.map((p) => [p.lat, p.lon]));
    const fit = () => map.fitBounds(bounds, {padding: [30, 30], maxZoom: 16});
    fit(); $('fit-route').addEventListener('click', fit);
    const endpoint = (point, color, label, index) => L.circleMarker([point.lat, point.lon], {radius: 7, color: '#fff', weight: 2, fillColor: color, fillOpacity: 1})
      .bindTooltip(label).on('click', () => select(index, false)).addTo(map);
    endpoint(points[0], '#078c65', '起点', 0);
    if (points.length > 1) endpoint(points[points.length - 1], '#df4b4b', '终点', points.length - 1);
    let selected = L.circleMarker([points[0].lat, points[0].lon], {radius: 10, color: '#e99b24', weight: 3, fillOpacity: .12}).addTo(map);
    function select(index, pan) {
      index = Math.max(0, Math.min(points.length - 1, index)); const point = points[index];
      $('track-position').value = index; text('selected-index', `${index + 1} / ${points.length}`); text('slider-count', `${index + 1} / ${points.length}`);
      text('selected-time', timestamp(point.fix_utc_ms || point.observed_utc_ms));
      facts($('point-facts'), [
        ['场景', point.label || '未记录'], ['网络', networks[point.network] || point.network || '未记录'],
        ['坐标', `${point.lat.toFixed(6)}, ${point.lon.toFixed(6)}`],
        ['定位精度', number(point.accuracy_m) ? `约 ${point.accuracy_m.toFixed(1)} 米` : '未记录'],
        ['位置距观测', number(point.location_age_ms) ? `${(point.location_age_ms / 1000).toFixed(1)} 秒` : '未记录'],
        ['定位来源', `${point.provider || '未记录'}${point.is_mock ? ' · 模拟位置' : ''}`]
      ]);
      text('point-up', rate(point.up_bps)); text('point-down', rate(point.down_bps));
      selected.setLatLng([point.lat, point.lon]); selected.bringToFront();
      if (pan && !map.getBounds().pad(-.1).contains([point.lat, point.lon])) map.panTo([point.lat, point.lon], {animate: false});
    }
    $('position-control').hidden = false; $('track-position').max = points.length - 1;
    $('track-position').addEventListener('input', (event) => select(Number(event.target.value), true));
    if (track.gap_count) text('gap-note', `${count(track.gap_count)} 处轨迹间隔已断开`);
    select(0, false);
  }
  start().catch((error) => { $('load-state').hidden = false; text('load-state', error.message || '加载失败，请刷新重试。'); });
})();
