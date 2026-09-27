"use client";

/**
 * 提示词集栏（`/functions`）：已加载的提示词集、保存当前提示词为提示词集、提示词集列表。
 *
 * 静态骨架逐块照搬 `webui/index.html` 的 `<!--#panel:functions-->`，id/class 原样保留。
 * 业务逻辑对应 `webui/app.js` 925–973 行（`loadFunctions` / `editFunctions` / `renderFunctions`）
 * 与 `bind()` 1612–1617 行。
 *
 * 与 Java 版的结构性差别：Java `renderFunctions` 每次 `innerHTML=''` 整表重画；React 用
 * `key={item.name}` 复用同名行（等价且更稳）。提示词集栏没有筛选框，所以这里不做增量 DOM 缓存。
 */
import { useCallback, useEffect, useRef, useState } from "react";
import { apiRequest, errorText, Unauthorized } from "../client/api";
import { Receipts } from "../client/capture";
import { useConsole } from "../client/store";

/** `/api/functions`（`lib/core/web-console.ts webFunctions`）与 `/api/functions/edit` 的返回结构。 */
type FunctionItem = {
  name: string;
  active?: boolean;
  positive?: string;
  negative?: string;
};

type FunctionsPayload = {
  functions?: FunctionItem[];
  active?: string[];
  message?: string;
};

export function FunctionsPanel() {
  const { unlocked, status, toast, showBanner, askDialog, showInfo } = useConsole();

  const [functions, setFunctions] = useState<FunctionItem[]>([]);
  /** `#function-active` 的文案（`app.js renderFunctions` 946）。 */
  const [activeText, setActiveText] = useState("");
  /** 首次读到列表前保持空白（同 Java：读接口前列表是空的，没有占位）。 */
  const [loaded, setLoaded] = useState(false);

  /** `#function-save-name` 按 Java 的做法"点的时候读 DOM"（不受控，用 ref）。 */
  const saveName = useRef<HTMLInputElement | null>(null);
  const scope = status?.scope;

  /** `app.js renderFunctions`(945)：已加载那句文案 + 列表（`active` 行给 `current`、名称后加 ★）。 */
  const renderFunctions = useCallback((data: FunctionsPayload) => {
    setLoaded(true);
    setActiveText("已加载：" + ((data.active || []).join("、") || "（无）"));
    setFunctions(data.functions || []);
  }, []);

  /** `app.js loadFunctions`(927)：读列表。刷新失败时保留手上这份列表，不显示空列表。 */
  const loadFunctions = useCallback(async () => {
    try {
      renderFunctions(
        await apiRequest<FunctionsPayload>("/api/functions", { method: "POST", body: { scope } }),
      );
    } catch (error) {
      if (error instanceof Unauthorized) return;
      // 同 `app.js loadPage`(1763)：「functions」面板加载失败：…
      showBanner("「functions」面板加载失败：" + errorText(error));
    }
  }, [renderFunctions, scope, showBanner]);

  /**
   * `app.js editFunctions`(932)：保存/覆盖/加载/移出/清空/重置/改名/删除都走 `/api/functions/edit`，
   * 返回的就是刷新后的整份列表（直接拿来重渲染）。
   */
  const editFunctions = useCallback(
    async (action: string, name: string, extra?: Record<string, unknown>) => {
      try {
        const data = await apiRequest<FunctionsPayload>("/api/functions/edit", {
          method: "POST",
          body: Object.assign({ action, name: name || "", scope }, extra || {}),
        });
        renderFunctions(data);
        if (data.message) toast(data.message);
        return data;
      } catch (error) {
        if (!(error instanceof Unauthorized)) toast(errorText(error));
        await loadFunctions().catch(() => {});
        return null;
      }
    },
    [loadFunctions, renderFunctions, scope, toast],
  );

  // 进栏目先读一次（`app.js loadPage`(1750)）；只有登录后才发请求。
  useEffect(() => {
    if (!unlocked) return;
    void loadFunctions();
  }, [unlocked, loadFunctions]);

  // app.js bind() 1612–1617
  return (
    <section className="panel active" id="panel-functions">
      <div className="card">
        <div className="card-head"><b>已加载</b><span className="muted" id="function-active">{activeText}</span></div>
        <div className="row wrap">
          {/* app.js 1615 / 1616：全部移出（remove）与只清除关联（reset） */}
          <button className="ghost" type="button" id="function-clear" onClick={() => void editFunctions("clear", "")}>全部移出</button>
          <button className="ghost" type="button" id="function-reset" onClick={() => void editFunctions("reset", "")}>只清除关联</button>
          <button className="ghost" type="button" id="function-reload" onClick={() => void loadFunctions()}>刷新</button>
        </div>
      </div>

      <div className="card">
        <div className="card-head"><b>保存当前提示词为提示词集</b></div>
        <div className="row wrap">
          <input id="function-save-name" placeholder="提示词集名称" ref={saveName} />
          {/* app.js 1613 / 1614：保存与覆盖 */}
          <button
            type="button"
            id="function-save"
            onClick={() => {
              const name = (saveName.current?.value ?? "").trim();
              if (name) void editFunctions("save", name);
            }}
          >
            保存
          </button>
          <button
            className="ghost"
            type="button"
            id="function-overwrite"
            onClick={() => {
              const name = (saveName.current?.value ?? "").trim();
              if (name) void editFunctions("overwrite", name);
            }}
          >
            覆盖
          </button>
        </div>
      </div>

      <ul className="list" id="function-list">
        {/* app.js renderFunctions(950)：key=名称复用同名行，`current` 行是已加载的那条。 */}
        {functions.map((item) => (
          <li className={item.active ? "current" : undefined} key={item.name}>
            <div className="name">
              {item.name + (item.active ? " ★" : "")}
              {item.positive ? <div className="sub">{item.positive.slice(0, 90)}</div> : null}
            </div>
            <div className="acts">
              {/* app.js 957：已加载的叫「移出」（remove），没加载的叫「加载」（load）。 */}
              <button
                type="button"
                onClick={() => void editFunctions(item.active ? "remove" : "load", item.name)}
              >
                {item.active ? "移出" : "加载"}
              </button>
              {/* app.js 959：查看原文（定义里有正反两段）。 */}
              <button
                className="ghost"
                type="button"
                onClick={() =>
                  void showInfo(
                    "提示词集：" + item.name,
                    "正向：\n" + (item.positive || "（空）") + "\n\n反向：\n" + (item.negative || "（空）"),
                    { text: "定义共享，加载关联按个人：加载会把这些词条追加到你个人的 prompt。" },
                  )
                }
              >
                查看
              </button>
              {/* app.js 962：改名走输入框模式的确认框（Java 的 askInput），不是浏览器 prompt。 */}
              <button
                className="ghost"
                type="button"
                onClick={() => {
                  void (async () => {
                    const target = await askDialog({
                      title: "提示词集改名",
                      text: "",
                      value: item.name,
                      label: "新名称",
                      confirmText: "改名",
                    });
                    if (target) void editFunctions("rename", item.name, { newName: target });
                  })();
                }}
              >
                改名
              </button>
              {/* app.js 966：删除要确认 */}
              <button
                className="danger"
                type="button"
                onClick={() => {
                  void (async () => {
                    const confirmed = await askDialog({
                      title: "删除提示词集",
                      text: "删除提示词集「" + item.name + "」？",
                      confirmText: "删除",
                      danger: true,
                    });
                    if (!confirmed) return;
                    void editFunctions("delete", item.name);
                  })();
                }}
              >
                删除
              </button>
            </div>
          </li>
        ))}
        {loaded && functions.length === 0 ? <li className="muted">还没有保存过提示词集。</li> : null}
      </ul>
      <Receipts id="function-receipts" />
    </section>
  );
}
