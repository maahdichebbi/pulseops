import { useEffect, useMemo, useRef, useState, type PointerEvent } from 'react';
import { geoDistance, geoGraticule10, geoOrthographic, geoPath } from 'd3-geo';
import { feature } from 'topojson-client';
import type { Topology, GeometryCollection } from 'topojson-specification';
import worldAtlas from 'world-atlas/countries-110m.json';
import type { Monitor } from '../api/types';

interface GlobalGlobeProps {
  monitors: Monitor[];
  onSelect: (id: number) => void;
}

const SIZE = 320;

export function GlobalGlobe({ monitors, onSelect }: GlobalGlobeProps) {
  const [rotation, setRotation] = useState<[number, number]>([-15, -20]);
  const [dragging, setDragging] = useState(false);
  const [pausedSpin, setPausedSpin] = useState(false);
  const [hovered, setHovered] = useState<Monitor | null>(null);
  const dragStartRef = useRef<{ x: number; y: number; rot: [number, number] } | null>(null);

  // Gentle auto-rotation unless the user is dragging or hovering
  useEffect(() => {
    if (dragging || pausedSpin) return;
    const id = window.setInterval(() => {
      setRotation(([lon, lat]) => [(lon + 0.35) % 360, lat]);
    }, 40);
    return () => window.clearInterval(id);
  }, [dragging, pausedSpin]);

  const { landPaths, graticulePath, visiblePins } = useMemo(() => {
    const projection = geoOrthographic()
      .scale(142)
      .translate([SIZE / 2, SIZE / 2])
      .rotate([rotation[0], rotation[1]])
      .clipAngle(90);

    const pathGen = geoPath(projection);
    const topology = worldAtlas as unknown as Topology<{
      countries: GeometryCollection;
    }>;
    const countries = feature(topology, topology.objects.countries);

    const land = countries.features
      .map((f, idx) => ({ id: f.id ?? idx, d: pathGen(f) ?? '' }))
      .filter((p) => p.d.length > 0);

    const grat = pathGen(geoGraticule10()) ?? '';

    const center: [number, number] = [-rotation[0], -rotation[1]];
    const pins = monitors
      .filter((m) => m.geoLat != null && m.geoLon != null)
      .filter((m) => geoDistance([m.geoLon!, m.geoLat!], center) < Math.PI / 2)
      .map((m) => {
        const coords = projection([m.geoLon!, m.geoLat!]);
        return coords ? { monitor: m, x: coords[0], y: coords[1] } : null;
      })
      .filter((p): p is { monitor: Monitor; x: number; y: number } => p !== null);

    return { landPaths: land, graticulePath: grat, visiblePins: pins };
  }, [monitors, rotation]);

  function handlePointerDown(e: PointerEvent<SVGSVGElement>) {
    e.currentTarget.setPointerCapture(e.pointerId);
    setDragging(true);
    dragStartRef.current = { x: e.clientX, y: e.clientY, rot: rotation };
  }

  function handlePointerMove(e: PointerEvent<SVGSVGElement>) {
    if (!dragging || !dragStartRef.current) return;
    const dx = e.clientX - dragStartRef.current.x;
    const dy = e.clientY - dragStartRef.current.y;
    const nextLon = dragStartRef.current.rot[0] + dx * 0.45;
    const nextLat = Math.max(-75, Math.min(75, dragStartRef.current.rot[1] - dy * 0.45));
    setRotation([nextLon, nextLat]);
  }

  function handlePointerUp(e: PointerEvent<SVGSVGElement>) {
    if (dragging) {
      e.currentTarget.releasePointerCapture(e.pointerId);
    }
    setDragging(false);
    dragStartRef.current = null;
  }

  function pinColor(m: Monitor) {
    if (!m.enabled) return '#8690a2';
    if (m.lastStatus === 'DOWN') return '#ef4444';
    if (m.lastStatus === 'UP') return '#22c55e';
    return '#eab308';
  }

  const geolocatedCount = monitors.filter((m) => m.geoLat != null && m.geoLon != null).length;

  return (
    <section className="card globe-card">
      <div className="globe-layout">
        <div className="globe-canvas-wrap">
          <svg
            viewBox={`0 0 ${SIZE} ${SIZE}`}
            className="globe-svg"
            style={{ cursor: dragging ? 'grabbing' : 'grab' }}
            onPointerDown={handlePointerDown}
            onPointerMove={handlePointerMove}
            onPointerUp={handlePointerUp}
            onPointerCancel={handlePointerUp}
            onMouseEnter={() => setPausedSpin(true)}
            onMouseLeave={() => { setPausedSpin(false); setHovered(null); }}
          >
            {/* Earth sphere */}
            <circle
              cx={SIZE / 2}
              cy={SIZE / 2}
              r={142}
              fill="#070a10"
              stroke="#242d40"
              strokeWidth="1"
            />
            <path d={graticulePath} fill="none" stroke="#131926" strokeWidth="0.6" />

            {/* Countries */}
            <g>
              {landPaths.map((c) => (
                <path
                  key={c.id}
                  d={c.d}
                  fill="#151b28"
                  stroke="#222a3c"
                  strokeWidth="0.5"
                />
              ))}
            </g>

            {/* Monitor pins */}
            {visiblePins.map(({ monitor, x, y }) => {
              const color = pinColor(monitor);
              return (
                <g
                  key={monitor.id}
                  style={{ cursor: 'pointer' }}
                  onMouseEnter={() => setHovered(monitor)}
                  onClick={(e) => {
                    e.stopPropagation();
                    onSelect(monitor.id);
                  }}
                >
                  <circle
                    cx={x}
                    cy={y}
                    r="5"
                    fill="none"
                    stroke={color}
                    strokeWidth="1.4"
                    className="map-pulse-ring"
                    style={{ transformOrigin: `${x}px ${y}px` }}
                  />
                  <circle cx={x} cy={y} r="4.5" fill={color} stroke="#070a10" strokeWidth="1.5" />
                </g>
              );
            })}
          </svg>
        </div>

        <div className="globe-info">
          <div>
            <h3>Global endpoints</h3>
            <p className="muted" style={{ margin: '0.25rem 0 0', fontSize: '0.84rem' }}>
              Drag the globe to spin · Click any pin to inspect that endpoint.
            </p>
          </div>

          <div className="globe-readout">
            {hovered ? (
              <>
                <div className="fw-600">{hovered.name}</div>
                <div className="mono muted" style={{ fontSize: '0.78rem' }}>
                  {[hovered.geoCity, hovered.geoCountry].filter(Boolean).join(', ')} · {hovered.targetIp}
                </div>
                <div className="mono" style={{ fontSize: '0.78rem', marginTop: '0.2rem' }}>
                  <span style={{ color: pinColor(hovered) }}>
                    {!hovered.enabled ? 'PAUSED' : hovered.lastStatus ?? 'PENDING'}
                  </span>
                  {hovered.lastResponseMs != null && ` · ${hovered.lastResponseMs} ms`}
                </div>
              </>
            ) : (
              <div className="mono muted" style={{ fontSize: '0.8rem' }}>
                {geolocatedCount} of {monitors.length} {monitors.length === 1 ? 'site' : 'sites'} mapped
              </div>
            )}
          </div>
        </div>
      </div>
    </section>
  );
}
