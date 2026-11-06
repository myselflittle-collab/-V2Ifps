import { Job, downloadUrl, fmtBytes, nf } from '../api'

export function ResultPanel({ job, onRestart }: { job: Job; onRestart: () => void }) {
  const r = job.result!
  const v = r.validation as Record<string, any>
  const checks = [
    `${nf.format(r.total_images)} images, named ${r.first} to ${r.last} with no gaps`,
    `Every image is ${r.width} × ${r.height}, the source resolution`,
    `ZIP verified: ${nf.format(v.zip_entries ?? 0)} entries, all checksums match`,
  ]
  return (
    <div className="result">
      <div className="result__head">
        <p className="result__count num">{nf.format(r.total_images)}</p>
        <p className="result__label">images ready</p>
      </div>
      <a className="btn btn--primary btn--lg" href={downloadUrl(job.id)} download>
        Download ZIP <span className="btn__meta num">{fmtBytes(r.zip_bytes)}</span>
      </a>
      <ul className="checks">
        {checks.map((c) => <li key={c}>{c}</li>)}
      </ul>
      {job.warnings.length > 0 && (
        <div className="note note--warn">
          {job.warnings.map((w) => <p key={w}>{w}</p>)}
        </div>
      )}
      <button className="btn btn--ghost" onClick={onRestart}>Change settings and extract again</button>
    </div>
  )
}
