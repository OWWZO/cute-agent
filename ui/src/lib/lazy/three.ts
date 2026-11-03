export type ThreeModule = typeof import("three");
export type OrbitControlsModule = typeof import(
  "three/examples/jsm/controls/OrbitControls.js"
);
export type GltfLoaderModule = typeof import(
  "three/examples/jsm/loaders/GLTFLoader.js"
);
export type RoomEnvironmentModule = typeof import(
  "three/examples/jsm/environments/RoomEnvironment.js"
);

function memoizedImport<T>(load: () => Promise<T>): () => Promise<T> {
  let pending: Promise<T> | undefined;
  return () => (pending ??= load());
}

export const loadThree = memoizedImport<ThreeModule>(() => import("three"));

export const loadOrbitControls = memoizedImport<OrbitControlsModule>(() =>
  import("three/examples/jsm/controls/OrbitControls.js")
);

export const loadGltfLoader = memoizedImport<GltfLoaderModule>(() =>
  import("three/examples/jsm/loaders/GLTFLoader.js")
);

export const loadRoomEnvironment = memoizedImport<RoomEnvironmentModule>(() =>
  import("three/examples/jsm/environments/RoomEnvironment.js")
);
