import { useEffect, useState } from "react";
import { withBase } from "../../base-path";
import { coordinate, type LatLng } from "./map-utils";

const cache = new Map<string, LatLng[]>();
const inFlight = new Map<string, Promise<LatLng[]>>();
const DEBOUNCE_MS = 700;
const FETCH_TIMEOUT_MS = 10_000;
const MAX_CACHE_ENTRIES = 64;

/**
 * Street geometry for a "lng,lat;lng,lat" key from /api/street-route, fetched in
 * the background so the answer never waits for a map service.
 * Returns points, `null` when routing failed, or `undefined` while loading.
 */
export function useStreetRoute(routingKey: string, enabled: boolean): LatLng[] | null | undefined {
  const [result, setResult] = useState<{ key: string; points: LatLng[] | null } | null>(null);
  useEffect(() => {
    if (!enabled || !routingKey.includes(";") || cache.has(routingKey)) return;
    let active = true;
    const debounce = setTimeout(() => {
      let request = inFlight.get(routingKey);
      if (!request) {
        const controller = new AbortController();
        let timeout: ReturnType<typeof setTimeout> | undefined;
        request = new Promise<LatLng[]>((resolve, reject) => {
          timeout = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);
          void fetch(
            withBase(`/api/street-route?coords=${encodeURIComponent(routingKey)}`),
            { signal: controller.signal }
          )
            .then(async (response) => {
              if (!response.ok) throw new Error("Route service unavailable");
              const data: { points?: unknown[] } = await response.json();
              const points = (data.points ?? []).flatMap((point) => {
                const result = Array.isArray(point)
                  ? coordinate({
                    lat: point[0],
                    lng: point[1],
                  })
                  : null;
                return result ? [result] : [];
              });
              if (points.length < 2) throw new Error("No street route available");
              resolve(points);
            })
            .catch(reject)
            .finally(() => {
              clearTimeout(timeout);
              inFlight.delete(routingKey);
            });
        });
        inFlight.set(routingKey, request);
      }
      void request.then((points) => {
        if (!cache.has(routingKey)) {
          if (cache.size >= MAX_CACHE_ENTRIES) {
            cache.delete(cache.keys().next().value!);
          }
          cache.set(routingKey, points);
        }
        if (active) {
          setResult({
            key: routingKey,
            points,
          });
        }
      }).catch(() => {
        if (active) {
          setResult({
            key: routingKey,
            points: null,
          });
        }
      });
    }, DEBOUNCE_MS);
    return () => {
      active = false;
      clearTimeout(debounce);
    };
  }, [routingKey, enabled]);
  return cache.get(routingKey) ?? (result?.key === routingKey ? result.points : undefined);
}
