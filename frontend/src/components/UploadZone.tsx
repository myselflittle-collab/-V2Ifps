import { useRef, useState } from 'react'

const ACCEPT = 'video/*,.mkv,.avi,.mov,.mp4,.m4v,.webm,.flv,.wmv,.mpg,.mpeg,.ts,.mts,.m2ts,.3gp,.ogv,.mxf,.y4m'

interface Props {
  onFile: (f: File) => void
  uploading: { name: string; fraction: number; processing: boolean } | null
  onCancel: () => void
}

export function UploadZone({ onFile, uploading, onCancel }: Props) {
  const input = useRef<HTMLInputElement>(null)
  const [over, setOver] = useState(false)

  if (uploading) {
    const pct = Math.round(uploading.fraction * 100)
    return (
      <div className="drop drop--busy" aria-live="polite">
        <p className="drop__title">{uploading.processing ? 'Reading video details…' : `Uploading ${pct}%`}</p>
        <p className="drop__file mono">{uploading.name}</p>
        <div className="bar"><div className={`bar__fill ${uploading.processing ? 'bar__fill--pulse' : ''}`} style={{ width: `${uploading.processing ? 100 : pct}%` }} /></div>
        {!uploading.processing && <button className="btn btn--ghost" onClick={onCancel}>Cancel upload</button>}
      </div>
    )
  }

  return (
    <div
      className={`drop ${over ? 'drop--over' : ''}`}
      onDragOver={(e) => { e.preventDefault(); setOver(true) }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => {
        e.preventDefault(); setOver(false)
        const f = e.dataTransfer.files?.[0]
        if (f) onFile(f)
      }}
      onClick={() => input.current?.click()}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && input.current?.click()}
    >
      <svg className="drop__icon" viewBox="0 0 64 40" aria-hidden="true">
        <rect x="1" y="1" width="62" height="38" rx="4" fill="none" stroke="currentColor" strokeWidth="2" />
        {[0, 1, 2, 3, 4, 5, 6].map((i) => (
          <g key={i}>
            <rect x={6 + i * 8} y="5" width="4" height="3" rx="0.5" fill="currentColor" />
            <rect x={6 + i * 8} y="32" width="4" height="3" rx="0.5" fill="currentColor" />
          </g>
        ))}
        <rect x="8" y="12" width="14" height="16" rx="1.5" className="drop__frame" />
        <rect x="25" y="12" width="14" height="16" rx="1.5" className="drop__frame" />
        <rect x="42" y="12" width="14" height="16" rx="1.5" className="drop__frame" />
      </svg>
      <p className="drop__title">Drop a video here, or <span className="link">browse your files</span></p>
      <p className="drop__hint">MP4, MOV, MKV, AVI, WebM and anything else FFmpeg can decode</p>
      <input
        ref={input} type="file" accept={ACCEPT} hidden
        onChange={(e) => { const f = e.target.files?.[0]; if (f) onFile(f); e.target.value = '' }}
      />
    </div>
  )
}
