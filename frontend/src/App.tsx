import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Estimate, Job, Settings, cancelJob, deleteJob, estimate as fetchEstimate, getJob, startExtract, uploadVideo,
} from './api'
import { Gallery } from './components/Gallery'
import { ProgressPanel } from './components/ProgressPanel'
import { ResultPanel } from './components/ResultPanel'
import { SettingsPanel } from './components/SettingsPanel'
import { UploadZone } from './components/UploadZone'
import { VideoSheet } from './components/VideoSheet'

const DEFAULTS: Settings = { format: 'png', quality: 100, zero_pad: false, start_frame: null, end_frame: null }
const RUNNING = ['extracting', 'validating', 'zipping']

export default function App() {
  const [job, setJob] = useState<Job | null>(null)
  const [settings, setSettings] = useState<Settings>(DEFAULTS)
  const [est, setEst] = useState<Estimate | null>(null)
  const [estimating, setEstimating] = useState(false)
  const [preview, setPreview] = useState<string | null>(null)
  const [uploading, setUploading] = useState<{ name: string; fraction: number; processing: boolean } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const abortUpload = useRef<() => void>(() => {})

  const running = !!job && RUNNING.includes(job.status)
  const done = job?.status === 'done'
  const info = job?.info ?? null

  // ---- upload --------------------------------------------------------------
  const onFile = useCallback(async (file: File) => {
    setError(null)
    if (job) deleteJob(job.id)
    setJob(null); setEst(null); setSettings(DEFAULTS)
    if (preview) URL.revokeObjectURL(preview)
    setPreview(URL.createObjectURL(file))
    setUploading({ name: file.name, fraction: 0, processing: false })
    const up = uploadVideo(file, (f) => setUploading({ name: file.name, fraction: f, processing: f >= 1 }))
    abortUpload.current = up.abort
    try {
      setJob(await up.promise)
    } catch (e) {
      setError((e as Error).message)
      setPreview(null)
    } finally {
      setUploading(null)
    }
  }, [job, preview])

  const reset = () => {
    if (job) deleteJob(job.id)
    if (preview) URL.revokeObjectURL(preview)
    setJob(null); setPreview(null); setEst(null); setError(null); setSettings(DEFAULTS)
  }

  // ---- size estimate (debounced) -------------------------------------------
  useEffect(() => {
    if (!job || running || done) return
    let stale = false
    setEstimating(true)
    const t = setTimeout(() => {
      fetchEstimate(job.id, settings)
        .then((e) => !stale && setEst(e))
        .catch(() => !stale && setEst(null))
        .finally(() => !stale && setEstimating(false))
    }, 300)
    return () => { stale = true; clearTimeout(t) }
  }, [job?.id, settings, running, done]) // eslint-disable-line react-hooks/exhaustive-deps

  // ---- polling while running -----------------------------------------------
  useEffect(() => {
    if (!job || !running) return
    let alive = true
    const tick = async () => {
      try {
        const j = await getJob(job.id)
        if (!alive) return
        setJob(j)
        if (j.status === 'error') setError(j.error)
      } catch (e) {
        if (alive) setError((e as Error).message)
      }
    }
    const t = setInterval(tick, 400)
    return () => { alive = false; clearInterval(t) }
  }, [job?.id, running]) // eslint-disable-line react-hooks/exhaustive-deps

  // Release server files when the tab closes
  useEffect(() => {
    const bye = () => { if (job) navigator.sendBeacon?.(`/api/jobs/${job.id}/cancel`) }
    window.addEventListener('pagehide', bye)
    return () => window.removeEventListener('pagehide', bye)
  }, [job])

  const extract = async () => {
    if (!job) return
    setError(null)
    try { setJob(await startExtract(job.id, settings)) } catch (e) { setError((e as Error).message) }
  }

  const cancel = async () => {
    if (!job) return
    try { setJob(await cancelJob(job.id)) } catch { /* poll will catch up */ }
  }

  const step = !info ? 1 : running ? 3 : done ? 4 : 2
  const rangeInvalid = settings.start_frame != null && settings.end_frame != null && settings.end_frame < settings.start_frame

  return (
    <div className="app">
      <header className="top">
        <a className="brand" href="/" onClick={(e) => { e.preventDefault(); reset() }}>
          <span className="brand__mark" aria-hidden="true"><i /><i /><i /></span>
          FrameX
        </a>
        <p className="top__tag">Every decoded frame of a video, at full resolution, numbered from 1.</p>
      </header>

      <main className={`layout ${info ? 'layout--loaded' : ''}`}>
        <div className="flow">
          <ol className="steps" aria-label="Progress through the extraction">
            {['Upload', 'Settings', 'Extract', 'Download'].map((s, i) => (
              <li key={s} className={i + 1 < step ? 'is-done' : i + 1 === step ? 'is-now' : ''}>{s}</li>
            ))}
          </ol>

          {error && (
            <div className="alert" role="alert">
              <p>{error}</p>
              <button className="btn btn--ghost btn--sm" onClick={() => setError(null)}>Dismiss</button>
            </div>
          )}

          {!info && <UploadZone onFile={onFile} uploading={uploading} onCancel={() => abortUpload.current()} />}

          {info && !running && !done && (
            <>
              <SettingsPanel settings={settings} totalFrames={info.frame_count} disabled={false} onChange={setSettings} />
              <button className="btn btn--primary btn--lg" onClick={extract} disabled={rangeInvalid}>
                Extract {est ? est.images.toLocaleString('en-US') : ''} frames
              </button>
              {rangeInvalid && <p className="err small">The end frame must come after the start frame.</p>}
              {job?.status === 'cancelled' && <p className="muted small">Extraction stopped. Temporary files were removed.</p>}
            </>
          )}

          {job && running && <ProgressPanel job={job} onCancel={cancel} />}
          {job && done && <ResultPanel job={job} onRestart={() => setJob({ ...job, status: 'uploaded', result: null })} />}
        </div>

        {info ? (
          <VideoSheet info={info} previewUrl={preview} estimate={est} estimating={estimating}
            format={settings.format} onReplace={reset} />
        ) : (
          <aside className="explainer">
            <h2>What you get</h2>
            <p>A 10-second clip at 30 fps becomes 300 images. At 120 fps, 1,200. FrameX decodes the video with FFmpeg and saves each frame exactly once, in order, without resizing or re-timing it.</p>
            <dl className="explainer__list">
              <div><dt>Original resolution</dt><dd>4K stays 4K. No scaling, cropping or enhancement.</dd></div>
              <div><dt>True frame count</dt><dd>Variable frame rate phone videos are counted frame by frame, not by duration × fps.</dd></div>
              <div><dt>Checked before download</dt><dd>File names, image sizes and the ZIP's checksums are verified before the download unlocks.</dd></div>
            </dl>
          </aside>
        )}
      </main>

      {job && done && job.result && (
        <Gallery jobId={job.id} total={job.result.total_images}
          fps={info && !info.is_vfr && !job.settings?.start_frame ? info.fps : 0} />
      )}

      <footer className="foot muted">Files are processed on this server and deleted automatically after two hours.</footer>
    </div>
  )
}
