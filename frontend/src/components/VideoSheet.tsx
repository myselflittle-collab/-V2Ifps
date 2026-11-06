import { useEffect, useRef, useState } from 'react'
import { Estimate, ImageFormat, VideoInfo, fmtBytes, fmtDuration, fmtFps, nf } from '../api'

/** One second of the video, drawn as one cell per frame. A playhead sweeps it
 *  once, in real time, at the video's own frame rate. */
function FrameRuler({ fps, vfr }: { fps: number; vfr: boolean }) {
  const cells = Math.max(1, Math.min(Math.round(fps), 240))
  const [lit, setLit] = useState(cells)
  const raf = useRef(0)

  const play = () => {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) { setLit(cells); return }
    cancelAnimationFrame(raf.current)
    const t0 = performance.now()
    const tick = (t: number) => {
      const n = Math.min(cells, Math.floor(((t - t0) / 1000) * cells) + 1)
      setLit(n)
      if (n < cells) raf.current = requestAnimationFrame(tick)
    }
    raf.current = requestAnimationFrame(tick)
  }

  useEffect(() => { play(); return () => cancelAnimationFrame(raf.current) }, [cells]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <figure className="ruler" onMouseEnter={play}>
      <div className="ruler__cells" style={{ gridTemplateColumns: `repeat(${cells}, 1fr)` }} aria-hidden="true">
        {Array.from({ length: cells }, (_, i) => (
          <span key={i} className={`ruler__cell ${i < lit ? 'is-lit' : ''} ${(i + 1) % 10 === 0 ? 'is-major' : ''}`} />
        ))}
      </div>
      <div className="ruler__scale muted" aria-hidden="true"><span>0 s</span><span>1 s</span></div>
      <figcaption className="ruler__caption">
        One second of this video is <strong className="num">{vfr ? 'about ' : ''}{fmtFps(fps)} images</strong>, not one.
      </figcaption>
    </figure>
  )
}

interface Props {
  info: VideoInfo
  previewUrl: string | null
  estimate: Estimate | null
  estimating: boolean
  format: ImageFormat
  onReplace: () => void
}

const FORMAT_NAME: Record<ImageFormat, string> = { png: 'PNG (lossless)', jpeg: 'JPEG', webp: 'WebP' }
const METHOD_NOTE: Record<VideoInfo['frame_count_method'], string> = {
  packets: 'counted from the stream',
  metadata: 'from container metadata',
  estimate: 'estimated from duration',
  unknown: 'determined during extraction',
}

export function VideoSheet({ info, previewUrl, estimate, estimating, format, onReplace }: Props) {
  const [canPlay, setCanPlay] = useState(true)
  const rows: [string, React.ReactNode][] = [
    ['Duration', <span className="num">{fmtDuration(info.duration)}</span>],
    ['Frame rate', <span className="num">{fmtFps(info.fps)} fps{info.is_vfr && <em className="tag">variable</em>}</span>],
    ['Resolution', <span className="num">{info.display_width} × {info.display_height}{info.rotation ? <em className="tag">rotated {info.rotation}°</em> : null}</span>],
    ['Codec', <span>{info.codec.toUpperCase()} <span className="muted">({info.pix_fmt})</span></span>],
    ['Total frames', <span className="num">{info.frame_count ? nf.format(info.frame_count) : '—'} <span className="muted">{METHOD_NOTE[info.frame_count_method]}</span></span>],
    ['Output images', <span className="num">{estimate ? nf.format(estimate.images) : '—'}</span>],
    ['Estimated size', <span className="num">{estimating ? 'measuring…' : estimate ? `≈ ${fmtBytes(estimate.total_bytes)}` : '—'}</span>],
    ['Image format', <span>{FORMAT_NAME[format]}</span>],
  ]
  return (
    <section className="sheet" aria-label="Video information">
      <div className="sheet__media">
        {previewUrl && canPlay ? (
          <video src={previewUrl} controls muted playsInline preload="metadata" onError={() => setCanPlay(false)} />
        ) : (
          <div className="sheet__noplay">Your browser can't preview this format. Extraction works regardless.</div>
        )}
      </div>
      <header className="sheet__head">
        <h2 className="sheet__name" title={info.filename}>{info.filename}</h2>
        <button className="btn btn--ghost btn--sm" onClick={onReplace}>Choose another video</button>
      </header>
      <p className="sheet__sub muted"><span>{info.container}</span><span className="num">{fmtBytes(info.size_bytes)}</span></p>
      <FrameRuler fps={info.fps} vfr={info.is_vfr} />
      <dl className="facts">
        {rows.map(([k, v]) => (
          <div className="facts__row" key={k}><dt>{k}</dt><dd>{v}</dd></div>
        ))}
      </dl>
      {info.is_vfr && (
        <p className="note">This video has a variable frame rate. FrameX extracts every decoded frame as-is, so the final count is the true number of frames, which may differ from duration × fps.</p>
      )}
    </section>
  )
}
