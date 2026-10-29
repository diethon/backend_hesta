import { useState } from 'react';
import { useAppDispatch, useAppSelector } from '../../store/hooks';
import { layoutDraftChanged, layoutEditingCancelled, layoutEditingStarted, loadLayoutRole, loadTwinLayout, saveTwinLayout } from '../../store/twinLayoutSlice';
import { TwinCanvas, type LayoutSelection } from './TwinCanvas';
import { TwinLayoutButton } from './TwinLayoutButton';
import { TwinLayoutInspector } from './TwinLayoutInspector';
import { TwinUnplacedPanel } from './TwinUnplacedPanel';
import type { TwinLayoutGeometry } from '../../types/twinLayout';
import { Expand, MousePointer2, Move, Trash2 } from 'lucide-react';

export function TwinLayoutEditor({ homeId }: { homeId: string }) {
  const state = useAppSelector((root) => root.twinLayout);
  const dispatch = useAppDispatch();
  const [selection, setSelection] = useState<LayoutSelection | null>(null);
  if (state.homeId !== homeId) return null;
  const { confirmed, draft, dirty, loading, saving, error, role, roleError, roleRequestId } = state;
  const geometry = draft ?? confirmed;
  const blocked = loading || saving;
  const editable = !!draft && role === 'OWNER' && !blocked;
  const change = (next: TwinLayoutGeometry) => dispatch(layoutDraftChanged(next));
  const confirmDiscard = () => window.confirm('Bỏ các thay đổi sơ đồ chưa lưu? Dữ liệu thiết bị và cảm biến mới nhất vẫn được giữ.');
  const reload = () => { if (!dirty || confirmDiscard()) void dispatch(loadTwinLayout(homeId)); };
  return <section aria-label="Sơ đồ Digital Twin" className="min-w-0 space-y-5">
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div><p className="text-sm font-semibold text-primary">Digital Twin · {homeId}</p><h2 className="mt-1 text-3xl font-bold tracking-tight text-text">Thiết kế không gian sống</h2><p role="status" className="mt-1 text-sm text-muted">{confirmed ? `Phiên bản ${confirmed.revision} · ${draft ? dirty ? 'Có thay đổi chưa lưu' : 'Đang chỉnh sửa' : 'Chế độ xem'}` : 'Đang tải sơ đồ…'}</p></div>
      <div className="flex flex-wrap gap-2">{draft ? <>
        <TwinLayoutButton variant="primary" disabled={!dirty || blocked || role !== 'OWNER' || error?.kind === 'conflict' || error?.kind === 'forbidden'} onClick={() => void dispatch(saveTwinLayout(homeId))}>{saving ? 'Đang lưu…' : 'Lưu bố cục'}</TwinLayoutButton>
        <TwinLayoutButton disabled={blocked} onClick={() => { if (!dirty || confirmDiscard()) dispatch(layoutEditingCancelled()); }}>Hủy</TwinLayoutButton>
      </> : <>
        {role === 'OWNER' ? <TwinLayoutButton variant="primary" disabled={!confirmed || blocked} onClick={() => dispatch(layoutEditingStarted())}>Chỉnh sửa sơ đồ</TwinLayoutButton> : null}
        {confirmed ? <TwinLayoutButton disabled={blocked} onClick={reload}>Tải mới nhất</TwinLayoutButton> : null}
      </>}</div>
    </div>
    {roleRequestId ? <p role="status" className="text-sm text-muted">Đang kiểm tra quyền chỉnh sửa…</p> : null}
    {role === 'MEMBER' ? <p className="text-sm text-muted">Thành viên · Chỉ xem sơ đồ.</p> : null}
    {roleError ? <div role="alert" className="rounded-xl bg-warning-soft p-4"><p>{roleError}</p><TwinLayoutButton onClick={() => void dispatch(loadLayoutRole(homeId))}>Kiểm tra lại quyền</TwinLayoutButton></div> : null}
    {error ? <div role="alert" className="space-y-3 rounded-xl border border-error bg-error-soft p-4">
      <p>{error.message}</p>{error.kind === 'forbidden' ? <TwinLayoutButton disabled={!!roleRequestId} onClick={() => void dispatch(loadLayoutRole(homeId))}>Kiểm tra lại quyền</TwinLayoutButton> : null}
      {error.kind === 'conflict' || !draft ? <TwinLayoutButton disabled={blocked} onClick={reload}>{error.kind === 'conflict' ? 'Tải sơ đồ mới nhất' : 'Thử tải lại sơ đồ'}</TwinLayoutButton> : null}
      {draft && error.kind === 'error' ? <p className="text-sm">Bản nháp vẫn được giữ. Bạn có thể sửa và chọn Lưu sơ đồ để thử lại.</p> : null}
    </div> : null}
    {loading ? <p role="status" className="rounded-xl bg-info-soft p-4 text-sm">Đang tải sơ đồ…</p> : null}
    {geometry ? <div className="grid min-w-0 gap-4 xl:grid-cols-[minmax(0,1fr)_21rem]">
      <div className="min-w-0 space-y-3">
        <div className="surface-card flex flex-wrap items-center gap-3 p-3 sm:p-4">
          <span className="rounded-xl bg-info-soft px-3 py-2 text-sm font-semibold text-text">{draft ? 'Chế độ chỉnh sửa' : 'Chế độ xem'}</span>
          <span className="text-sm text-muted">{draft ? 'Kéo phòng, thiết bị và cảm biến trên mặt bằng' : 'Trạng thái trực tiếp theo vị trí đã lưu'}</span>
          <span className="ml-auto hidden items-center gap-3 text-xs text-muted sm:flex"><span className="h-2.5 w-2.5 rounded-full bg-info" />Phòng <span className="h-2.5 w-2.5 rounded-full bg-mint" />Thiết bị <span className="h-2.5 w-2.5 rounded-full bg-primary" />Cảm biến</span>
        </div>
        {draft ? <div className="grid gap-2 sm:grid-cols-4">
          {[['Drag & drop', 'Đặt phòng, thiết bị, cảm biến', MousePointer2], ['Move', 'Kéo đối tượng trên sơ đồ', Move], ['Resize', 'Kéo góc phòng', Expand], ['Delete', 'Bỏ vị trí hiển thị', Trash2]].map(([title, description, Icon]) => <div key={title as string} className="surface-card flex min-w-0 items-center gap-3 p-3"><span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-info-soft text-primary"><Icon size={18} aria-hidden="true" /></span><span className="min-w-0"><strong className="block truncate text-xs text-text">{title as string}</strong><span className="block truncate text-[11px] text-muted">{description as string}</span></span></div>)}
        </div> : null}
        <TwinCanvas key={draft ? 'edit' : 'view'} geometry={geometry} editable={editable} selection={selection} onSelect={setSelection} onChange={change} />
        <div className="surface-card flex w-fit items-center gap-2 p-2"><button type="button" aria-label="Thu nhỏ sơ đồ" className="flex h-9 w-9 items-center justify-center rounded-lg text-lg text-muted hover:bg-sidebar-hover">−</button><span className="min-w-14 text-center text-sm font-semibold text-text">100%</span><button type="button" aria-label="Phóng to sơ đồ" className="flex h-9 w-9 items-center justify-center rounded-lg text-lg text-text hover:bg-sidebar-hover">+</button><span className="mx-1 h-6 w-px bg-line" /><button type="button" aria-label="Đưa sơ đồ vừa màn hình" className="flex min-h-9 items-center gap-2 rounded-lg px-3 text-sm font-semibold text-text hover:bg-sidebar-hover"><Expand size={16} aria-hidden="true" />Vừa màn hình</button></div>
        <p className="text-xs text-muted">{draft ? 'Kéo ↘ ở góc phòng để thay đổi kích thước. Mọi thay đổi chỉ được lưu khi bạn bấm Lưu bố cục.' : 'Bố cục được lấy từ phiên bản đã lưu.'}</p>
      </div>
      <aside className="min-w-0 space-y-4">
        {draft ? <TwinUnplacedPanel geometry={geometry} disabled={!editable} onChange={change} /> : null}
        <TwinLayoutInspector geometry={geometry} selection={selection} editable={editable} onChange={change} />
      </aside>
    </div> : null}
  </section>;
}
