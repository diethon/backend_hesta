import { useEffect } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useAppDispatch, useAppSelector } from '../../store/hooks';
import { currentHomeChanged, currentHomeCleared } from '../../store/homeSlice';
import { loadTwinSnapshot, twinClosed, twinOpened } from '../../store/twinSlice';
import { AppSidebar, DeviceIcon, HomeIcon, TwinIcon } from '../ui/AppSidebar';
import { TwinContent } from './TwinContent';
import { TwinLayoutEditor } from './TwinLayoutEditor';
import { loadLayoutRole, loadTwinLayout } from '../../store/twinLayoutSlice';
import { useLayoutNavigationGuard } from './useLayoutNavigationGuard';

const connectionLabels = {
  connected: 'Đã kết nối', connecting: 'Đang kết nối…', reconnecting: 'Đang kết nối lại…',
  disconnected: 'Đã ngắt kết nối', error: 'Kết nối bị gián đoạn',
};

export function DigitalTwinView({ homeId }: { homeId: string }) {
  const dispatch = useAppDispatch();
  const matchesHome = useAppSelector((state) => state.twin.homeId === homeId);
  const home = useAppSelector((state) => state.twin.home);
  const loading = useAppSelector((state) => state.twin.loading);
  const initialized = useAppSelector((state) => state.twin.initialized);
  const error = useAppSelector((state) => state.twin.error);
  const status = useAppSelector((state) => state.realtime.status);
  const subscribedHome = useAppSelector((state) => state.realtime.activeHomeId);
  const connected = status === 'connected' && subscribedHome === homeId;
  const connectionLabel = status === 'connected' && !connected ? 'Đang đăng ký nhà…' : connectionLabels[status];
  return <div className="mx-auto max-w-7xl space-y-6 p-4 sm:p-6 lg:p-8">
    <nav aria-label="Điều hướng Digital Twin" className="flex flex-wrap gap-3 text-sm font-medium text-text">
      <Link className="rounded-xl border border-line bg-surface px-4 py-3 hover:bg-sidebar-hover" to="/home">← Chọn nhà</Link>
      <Link className="rounded-xl border border-line bg-surface px-4 py-3 hover:bg-sidebar-hover" to={`/home/${encodeURIComponent(homeId)}/devices`}>Thiết bị</Link>
    </nav>
    <header className="surface-card flex flex-wrap items-start justify-between gap-4 p-5 sm:p-6">
      <div className="min-w-0">
        <p className="text-sm font-medium text-muted">Không gian sống{matchesHome && home ? ` · ${home.name}` : ''}</p>
        <h1 className="mt-2 break-words text-3xl font-bold tracking-tight">Digital Twin</h1>
        <p className="mt-2 text-sm text-muted">Trực quan hóa và quản lý bố cục ngôi nhà của bạn.</p>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <p role="status" className={`rounded-full border px-3 py-2 text-xs font-semibold text-text ${connected ? 'border-success bg-success-soft' : 'border-warning bg-warning-soft'}`}>Realtime: {connectionLabel}</p>
        <button type="button" disabled={loading || !matchesHome} onClick={() => void dispatch(loadTwinSnapshot(homeId))}
          className="rounded-xl border border-line bg-sidebar px-4 py-3 text-sm font-semibold text-text hover:bg-sidebar-hover disabled:opacity-50">{loading && initialized ? 'Đang đồng bộ…' : 'Đồng bộ lại'}</button>
      </div>
    </header>
    {!connected && matchesHome && initialized ? <p className="rounded-xl bg-warning-soft px-4 py-3 text-sm text-text">Đang hiển thị dữ liệu gần nhất. Sau khi kết nối lại, chọn “Đồng bộ lại” để nhận những thay đổi đã bỏ lỡ.</p> : null}
    {matchesHome && error ? <div role="alert" className="rounded-2xl border border-error bg-error-soft p-5 text-text">
      <p>{error}</p><button type="button" disabled={loading} onClick={() => void dispatch(loadTwinSnapshot(homeId))} className="mt-3 rounded-xl bg-surface px-4 py-3 text-sm font-semibold disabled:opacity-50">Thử lại</button>
    </div> : null}
    {!matchesHome || (!initialized && !error) ? <div role="status" aria-label="Đang tải Digital Twin" className="surface-card p-6">
      <p className="text-sm text-muted">Đang tải Digital Twin…</p><div className="mt-4 h-32 animate-pulse rounded-2xl bg-off-soft" />
    </div> : initialized ? <>
      <TwinLayoutEditor key={homeId} homeId={homeId} />
      <details className="surface-card p-4 sm:p-5"><summary className="cursor-pointer font-semibold">Dữ liệu trực tiếp · Tất cả phòng và đối tượng</summary><div className="mt-5"><TwinContent /></div></details>
    </> : null}
  </div>;
}

export function DigitalTwinPage() {
  useLayoutNavigationGuard();
  const { homeId = '' } = useParams<{ homeId: string }>();
  const dispatch = useAppDispatch();
  const navigate = useNavigate();
  useEffect(() => {
    dispatch(currentHomeChanged(homeId));
    dispatch(twinOpened(homeId));
    const request = dispatch(loadTwinSnapshot(homeId));
    const layoutRequest = dispatch(loadTwinLayout(homeId));
    const roleRequest = dispatch(loadLayoutRole(homeId));
    return () => {
      request.abort();
      layoutRequest.abort();
      roleRequest.abort();
      dispatch(twinClosed());
      dispatch(currentHomeCleared());
    };
  }, [dispatch, homeId]);
  return <div className="app-shell">
    <AppSidebar activeItem="twin" contextLabel="Không gian sống" showSystemSummary={false} items={[
      { id: 'home', label: 'Tổng quan', icon: <HomeIcon />, onClick: () => navigate('/home') },
      { id: 'devices', label: 'Thiết bị', icon: <DeviceIcon />, onClick: () => navigate(`/home/${encodeURIComponent(homeId)}/devices`) },
      { id: 'twin', label: 'Digital Twin', icon: <TwinIcon />, onClick: () => window.scrollTo({ top: 0 }) },
    ]} />
    <main id="main-content" className="lg:pl-64"><DigitalTwinView homeId={homeId} /></main>
  </div>;
}
