import { Job, fmtEta, nf } from '../api'

const STAGES: { id: Job['status']; label: string }[] = [
  { id: 'extracting', label: 'Extract' },
  { id: 'validating', label: 'Validate' },
  { id: 'zipping', label: 'Package ZIP' },
]

export function ProgressPanel({ job, onCancel }: { job: Job; onCancel: () => void }) {
  const idx = STAGES.findIndex((s) => s.id === job.status)
  const pct = job.progress
  return (
    <div className="progress" aria-live="polite">
      <div className="progress__top">
        <span className="progress__pct num">{pct.toFixed(1)}%</span>
        <span className="progress__stage">{job.stage}</span>
      </div>
      <div className="bar bar--lg" role="progressbar" aria-valuenow={Math.round(pct)} aria-valuemin={0} aria-valuemax={100}>
        <div className="bar__fill" style={{ width: `${pct}%` }} />
      </div>
      <ol className="stages">
        {STAGES.map((s, i) => (
          <li key={s.id} className={i < idx ? 'is-done' : i === idx ? 'is-now' : ''}>{s.label}</li>
        ))}
      </ol>
      <dl className="stats">
        <div><dt>Frames extracted</dt><dd className="num">{nf.format(job.frames_done)}<span className="muted"> / {job.expected_frames ? nf.format(job.expected_frames) : '?'}</span></dd></div>
        <div><dt>Current frame</dt><dd className="num">{job.current_frame ? nf.format(job.current_frame) : '—'}</dd></div>
        <div><dt>Speed</dt><dd className="num">{job.speed_fps ? `${job.speed_fps.toFixed(1)} frames/s` : '—'}</dd></div>
        <div><dt>Time left</dt><dd className="num">{job.status === 'extracting' ? fmtEta(job.eta_seconds) : 'finishing up'}</dd></div>
      </dl>
      <button className="btn btn--ghost" onClick={onCancel}>Stop extraction</button>
    </div>
  )
}
