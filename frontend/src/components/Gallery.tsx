import { useCallback, useEffect, useRef, useState } from 'react'
import { frameUrl, listFrames, nf, thumbUrl } from '../api'

const PAGE = 120

interface Props { jobId: string; total: number; fps: number }

export function Gallery({ jobId, total, fps }: Props) {
  const [names, setNames] = useState<string[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<number | null>(null)
  const [size, setSize] = useState(160)
  const sentinel = useRef<HTMLDivElement>(null)

  const loadMore = useCallback(async () => {
    if (loading) return
    setLoading(true)
    try {
      const page = await listFrames(jobId, names.length, PAGE)
      setNames((prev) => (prev.length === page.offset ? [...prev, ...page.names] : prev))
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setLoading(false)
    }
  }, [jobId, names.length, loading])

  useEffect(() => { setNames([]); setOpen(null) }, [jobId])
  useEffect(() => { if (names.length === 0 && total > 0) loadMore() }, [names.length, total]) // eslint-disable-line react-hooks/exhaustive-deps

  // Infinite scroll
  useEffect(() => {
    const el = sentinel.current
    if (!el) return
    const io = new IntersectionObserver((e) => {
      if (e[0].isIntersecting && names.length < total) loadMore()
    }, { rootMargin: '600px' })
    io.observe(el)
    return () => io.disconnect()
  }, [loadMore, names.length, total])

  const timecode = (i: number) => (fps ? `${(i / fps).toFixed(3)} s` : '')

  return (
    <section className="gallery" aria-label="Extracted frames">
      <header className="gallery__head">
        <div>
          <h2>Frames</h2>
          <p className="muted">Showing {nf.format(names.length)} of {nf.format(total)}. Select a frame to view it at full size.</p>
        </div>
        <label className="gallery__zoom">
          <span className="muted">Thumbnail size</span>
          <input type="range" min={96} max={320} step={16} value={size} onChange={(e) => setSize(Number(e.target.value))} />
        </label>
      </header>
      <div className="grid" style={{ gridTemplateColumns: `repeat(auto-fill, minmax(${size}px, 1fr))` }}>
        {names.map((n, i) => (
          <button key={n} className="tile" onClick={() => setOpen(i)} aria-label={`Open frame ${n}`}>
            <img src={thumbUrl(jobId, n)} alt="" loading="lazy" decoding="async" />
            <span className="tile__name mono">{n}</span>
          </button>
        ))}
      </div>
      <div ref={sentinel} className="gallery__more">
        {error ? <p className="err">{error}</p>
          : names.length < total ? <button className="btn btn--ghost" onClick={loadMore} disabled={loading}>{loading ? 'Loading frames…' : 'Load more frames'}</button>
          : null}
      </div>
      {open != null && names[open] && (
        <Lightbox
          jobId={jobId} name={names[open]} index={open} total={total} time={timecode(open)}
          onClose={() => setOpen(null)}
          onStep={(d) => {
            const next = open + d
            if (next < 0 || next >= total) return
            if (next >= names.length) loadMore()
            else setOpen(next)
          }}
        />
      )}
    </section>
  )
}

function Lightbox({ jobId, name, index, total, time, onClose, onStep }: {
  jobId: string; name: string; index: number; total: number; time: string
  onClose: () => void; onStep: (d: number) => void
}) {
  const close = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    close.current?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
      if (e.key === 'ArrowRight') onStep(1)
      if (e.key === 'ArrowLeft') onStep(-1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose, onStep])

  return (
    <div className="lightbox" role="dialog" aria-modal="true" aria-label={`Frame ${name}`} onClick={onClose}>
      <div className="lightbox__inner" onClick={(e) => e.stopPropagation()}>
        <img src={frameUrl(jobId, name)} alt={`Frame ${index + 1}`} />
        <div className="lightbox__bar">
          <span className="mono">{name}</span>
          <span className="muted num">Frame {nf.format(index + 1)} of {nf.format(total)}{time && `, at ${time}`}</span>
          <span className="lightbox__actions">
            <button className="btn btn--ghost btn--sm" onClick={() => onStep(-1)} disabled={index === 0}>Previous</button>
            <button className="btn btn--ghost btn--sm" onClick={() => onStep(1)} disabled={index >= total - 1}>Next</button>
            <a className="btn btn--ghost btn--sm" href={frameUrl(jobId, name)} download={name}>Save image</a>
            <button ref={close} className="btn btn--ghost btn--sm" onClick={onClose}>Close</button>
          </span>
        </div>
      </div>
    </div>
  )
}
