// @openuidev/react-lang 在开发模式下会自挂载 OpenUI Inspect 悬浮球（@openuidev/devtools）。
// 这是它 bootstrap 里检查的全局标记，预置后即可跳过自动挂载；
// 生产构建里那段副作用本来就会被 NODE_ENV 判断 DCE 掉，这里只为 dev 屏蔽。
(globalThis as Record<PropertyKey, unknown>)[Symbol.for('openui.devtools.autoMount')] = true;

export {};
