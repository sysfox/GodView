/* Drawing.
 *
 * Canvas, by hand. The panel ships inside a jar with no external assets, and a
 * charting library would be an order of magnitude more code than the two shapes
 * this console actually needs: a tick ribbon and a line. */

export const COLOR = {
  slate: '#39434F',
  ash: '#8A95A6',
  dim: '#5E6878',
  vein: '#2A3440',
  lichen: '#7FB069',
  amber: '#E0A458',
  redstone: '#D64550',
  lapis: '#5B8BD0',
  void: '#0E1116',
};

/** A tick is nominally 50 ms. These are where it stops being fine. */
export const TICK_WARN = 75;
export const TICK_BAD = 120;

function fit(canvas) {
  const ratio = window.devicePixelRatio || 1;
  const width = canvas.clientWidth || canvas.width;
  const height = canvas.clientHeight || canvas.height;
  const wantW = Math.max(1, Math.round(width * ratio));
  const wantH = Math.max(1, Math.round(height * ratio));
  if (canvas.width !== wantW || canvas.height !== wantH) {
    canvas.width = wantW;
    canvas.height = wantH;
  }
  const ctx = canvas.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  return { ctx, width, height };
}

function tickColor(ms) {
  if (ms >= TICK_BAD) return COLOR.redstone;
  if (ms >= TICK_WARN) return COLOR.amber;
  return COLOR.slate;
}

/**
 * The tick ribbon: one bar per tick, height by duration.
 *
 * Monochrome until something is wrong. A healthy server draws an even slate
 * comb that recedes into the chrome, so a single overrun tick is the only
 * coloured thing on the page -- which is what makes it findable at a glance
 * rather than one more blinking indicator to learn to ignore.
 */
export function drawRibbon(canvas, ticks, options = {}) {
  if (!canvas) return;
  const { ctx, width, height } = fit(canvas);
  const gap = options.gap ?? 1;
  const barWidth = options.barWidth ?? 3;
  const step = barWidth + gap;
  const capacity = Math.max(1, Math.floor(width / step));
  const shown = ticks.slice(-capacity);

  ctx.clearRect(0, 0, width, height);
  if (shown.length === 0) return;

  // The 50 ms line is a tick's whole budget. Drawing it means the ribbon can be
  // read without knowing the scale: below the line the server is keeping up.
  const ceiling = Math.max(TICK_BAD, ...shown);
  const y50 = height - (50 / ceiling) * height;

  ctx.strokeStyle = COLOR.vein;
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(0, Math.round(y50) + 0.5);
  ctx.lineTo(width, Math.round(y50) + 0.5);
  ctx.stroke();

  const offset = width - shown.length * step;
  shown.forEach((ms, index) => {
    const barHeight = Math.max(1, (Math.min(ms, ceiling) / ceiling) * height);
    ctx.fillStyle = tickColor(ms);
    ctx.fillRect(offset + index * step, height - barHeight, barWidth, barHeight);
  });
}

/**
 * A line chart.
 *
 * @param series [{ points: [{t, v}], color }]
 */
export function drawLine(canvas, series, options = {}) {
  if (!canvas) return;
  const { ctx, width, height } = fit(canvas);
  ctx.clearRect(0, 0, width, height);

  const padLeft = options.padLeft ?? 34;
  const padBottom = 16;
  const padTop = 8;
  const plotW = Math.max(1, width - padLeft - 6);
  const plotH = Math.max(1, height - padTop - padBottom);

  const all = series.flatMap((s) => s.points);
  if (all.length === 0) {
    ctx.fillStyle = COLOR.dim;
    ctx.font = '11px ui-monospace, monospace';
    ctx.textAlign = 'center';
    ctx.fillText('No data yet', width / 2, height / 2);
    return;
  }

  const minT = Math.min(...all.map((p) => p.t));
  const maxT = Math.max(...all.map((p) => p.t));
  const minV = options.min ?? Math.min(...all.map((p) => p.v));
  const maxV = options.max ?? Math.max(...all.map((p) => p.v));
  const spanT = Math.max(1, maxT - minT);
  const spanV = Math.max(1e-6, maxV - minV);

  const x = (t) => padLeft + ((t - minT) / spanT) * plotW;
  const y = (v) => padTop + plotH - ((v - minV) / spanV) * plotH;

  // Three gridlines. More is chart furniture; fewer and the reader cannot tell
  // whether a dip went to 19 or to 12.
  ctx.font = '10px ui-monospace, monospace';
  ctx.textAlign = 'right';
  ctx.textBaseline = 'middle';
  for (let i = 0; i <= 2; i += 1) {
    const value = minV + (spanV * i) / 2;
    const gy = Math.round(y(value)) + 0.5;
    ctx.strokeStyle = COLOR.vein;
    ctx.beginPath();
    ctx.moveTo(padLeft, gy);
    ctx.lineTo(width - 6, gy);
    ctx.stroke();
    ctx.fillStyle = COLOR.dim;
    ctx.fillText(label(value, options.decimals ?? 0), padLeft - 6, gy);
  }

  for (const line of series) {
    if (line.points.length === 0) continue;
    ctx.beginPath();
    line.points.forEach((point, index) => {
      const px = x(point.t);
      const py = y(point.v);
      if (index === 0) ctx.moveTo(px, py);
      else ctx.lineTo(px, py);
    });
    ctx.strokeStyle = line.color ?? COLOR.lapis;
    ctx.lineWidth = 1.5;
    ctx.lineJoin = 'round';
    ctx.stroke();
  }

  // Time is labelled at the ends only. A column of timestamps under a
  // 120-pixel chart is unreadable, and "an hour ago, to now" is the question.
  ctx.fillStyle = COLOR.dim;
  ctx.textBaseline = 'alphabetic';
  ctx.textAlign = 'left';
  ctx.fillText(ago(maxT - minT), padLeft, height - 3);
  ctx.textAlign = 'right';
  ctx.fillText('now', width - 6, height - 3);
}

function label(value, decimals) {
  if (Math.abs(value) >= 1000) return `${Math.round(value / 1000)}k`;
  return value.toFixed(decimals);
}

function ago(millis) {
  const minutes = Math.round(millis / 60000);
  if (minutes < 1) return `${Math.round(millis / 1000)}s`;
  if (minutes < 60) return `${minutes}m`;
  return `${Math.round(minutes / 60)}h`;
}

/** Turns a /api/series entry into chart points. */
export function toPoints(series) {
  if (!series || !series.t) return [];
  return series.t.map((t, i) => ({ t, v: series.avg[i] }));
}
