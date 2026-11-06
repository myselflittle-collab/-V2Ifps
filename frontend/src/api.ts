export type ImageFormat = 'png' | 'jpeg' | 'webp'

export interface VideoInfo {
  filename: string
  container: string
  codec: string
  codec_long: string
  pix_fmt: string
  width: number
  height: number
  display_width: number
  display_height: number
  rotation: number
  duration: number
  fps: number
  fps_fraction: string
  r_frame_rate: string
  is_vfr: boolean
  frame_count: number
  frame_count_method: 'packets' | 'metadata' | 'estimate' | 'unknown'
  bit_rate: number | null
  size_bytes: number
}

export interface Settings {
  format: ImageFormat
  quality: number
  zero_pad: boolean
  start_frame: number | null
  end_frame: number | null
}

export interface JobResult {
  total_images: number
  first: string | null
  last: string | null
  width: number
  height: number
  frames_bytes: number
  zip_bytes: number
  zip_ready: boolean
  validation: Record<string, unknown>
}

export type JobStatus = 'uploaded' | 'extracting' | 'validating' | 'zipping' | 'done' | 'error' | 'cancelled'

export interface Job {
  id: string
  status: JobStatus
  stage: string
  error: string | null
  warnings: string[]
  progress: number
  expected_frames: number
  frames_done: number
  current_frame: number
  speed_fps: number | null
  eta_seconds: number | null
  elapsed_seconds: number | null
  info: VideoInfo | null
  settings: Settings | null
  result: JobResult | null
}

export interface Estimate {
  images: number
  bytes_per_image: number
  total_bytes: number
  format: ImageFormat
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let msg = `${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      if (body?.detail) msg = typeof body.detail === 'string' ? body.detail : JSON.stringify(body.detail)
    } catch { /* keep status text */ }
    throw new Error(msg)
  }
  return res.json() as Promise<T>
}

/** Streams the raw file to the server. Uses XHR for upload progress. */
export function uploadVideo(file: File, onProgress: (fraction: number) => void): { promise: Promise<Job>; abort: () => void } {
  const xhr = new XMLHttpRequest()
  const promise = new Promise<Job>((resolve, reject) => {
    xhr.open('PUT', `/api/upload?filename=${encodeURIComponent(file.name)}`)
    xhr.setRequestHeader('Content-Type', 'application/octet-stream')
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total)
    xhr.onload = () => {
      let body: any = null
      try { body = JSON.parse(xhr.responseText) } catch { /* ignore */ }
      if (xhr.status >= 200 && xhr.status < 300) resolve(body as Job)
      else reject(new Error(body?.detail ?? `Upload failed (${xhr.status})`))
    }
    xhr.onerror = () => reject(new Error('Upload failed: the server could not be reached.'))
    xhr.onabort = () => reject(new Error('Upload cancelled.'))
    xhr.send(file)
  })
  return { promise, abort: () => xhr.abort() }
}

export const getJob = (id: string) => fetch(`/api/jobs/${id}`).then((r) => json<Job>(r))

export const estimate = (id: string, s: Settings) =>
  fetch(`/api/jobs/${id}/estimate`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(s),
  }).then((r) => json<Estimate>(r))

export const startExtract = (id: string, s: Settings) =>
  fetch(`/api/jobs/${id}/extract`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(s),
  }).then((r) => json<Job>(r))

export const cancelJob = (id: string) => fetch(`/api/jobs/${id}/cancel`, { method: 'POST' }).then((r) => json<Job>(r))

export const deleteJob = (id: string) => fetch(`/api/jobs/${id}`, { method: 'DELETE' }).catch(() => undefined)

export const listFrames = (id: string, offset: number, limit: number) =>
  fetch(`/api/jobs/${id}/frames?offset=${offset}&limit=${limit}`).then((r) =>
    json<{ total: number; offset: number; names: string[] }>(r))

export const frameUrl = (id: string, name: string) => `/api/jobs/${id}/frames/${encodeURIComponent(name)}`
export const thumbUrl = (id: string, name: string) => `/api/jobs/${id}/thumbs/${encodeURIComponent(name)}`
export const downloadUrl = (id: string) => `/api/jobs/${id}/download`

// ---- formatting helpers ----------------------------------------------------
export function fmtBytes(n: number): string {
  if (!n) return '0 B'
  const u = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.min(Math.floor(Math.log(n) / Math.log(1024)), u.length - 1)
  return `${(n / 1024 ** i).toFixed(i === 0 ? 0 : i === 1 ? 0 : 2)} ${u[i]}`
}

export function fmtDuration(sec: number): string {
  if (!isFinite(sec)) return '—'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  const ss = s.toFixed(2).padStart(5, '0')
  return h ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}

export function fmtEta(sec: number | null): string {
  if (sec == null) return '—'
  if (sec < 1) return 'under a second'
  if (sec < 60) return `${Math.ceil(sec)} s`
  const m = Math.floor(sec / 60)
  return `${m} min ${Math.round(sec % 60)} s`
}

export const nf = new Intl.NumberFormat('en-US')

export function fmtFps(fps: number): string {
  return Number.isInteger(Math.round(fps * 1000) / 1000) ? String(Math.round(fps)) : fps.toFixed(3)
}
