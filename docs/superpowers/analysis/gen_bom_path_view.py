#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
生成 BOM 变长路径视图的 SQL（部署期使用，不要运行时建视图）。

用法:
    python gen_bom_path_view.py --max-depth 9 > bom_path_view.sql
    python gen_bom_path_view.py --max-depth 5 --view bom_path_5

设计要点:
  * 每层都带 BOM 上下文（bom_code / bom_version），否则会跨 BOM 扇出。
  * 每层 SELECT DISTINCT，消除重复边。
  * 起点通过 start_bom_item_id 列在查询侧过滤，可下推到 level_0。
  * level_k 只 join level_{k-1}（链式），不是全量交叉。
"""

import argparse


def col_list(cols):
    return ",\n         ".join(cols)


def gen(max_depth, source, view, id_col, node_col, parent_col, context_cols):
    ctx = list(context_cols)
    ctx_select_l0 = "".join("          s.%s,\n" % c for c in ctx)
    ctx_select_lk = "".join("          l.%s,\n" % c for c in ctx)
    join_ctx = "".join("\n   AND    l.%s = e.%s" % (c, c) for c in ctx)

    parts = []

    # level_0
    parts.append(
        """level_0 AS (
  SELECT DISTINCT
          s.%(id)s AS start_bom_item_id,
%(ctx_select)s          s.%(id)s AS %(id)s,
          s.%(node)s AS %(node)s,
          s.%(parent)s AS %(parent)s,
          0 AS depth,
          s.%(id)s AS predecessor
  FROM    %(source)s s
)"""
        % dict(id=id_col, ctx_select=ctx_select_l0, node=node_col,
               parent=parent_col, source=source)
    )

    # level_1..N
    for k in range(1, max_depth + 1):
        parts.append(
            """level_%(k)d AS (
  SELECT DISTINCT
          l.start_bom_item_id,
%(ctx_select)s          e.%(id)s AS %(id)s,
          e.%(node)s AS %(node)s,
          e.%(parent)s AS %(parent)s,
          %(k)d AS depth,
          l.%(id)s AS predecessor
  FROM    %(source)s e
  JOIN    level_%(prev)d l
    ON    l.%(parent)s = e.%(node)s%(join_ctx)s
)"""
            % dict(k=k, prev=k - 1, id=id_col, node=node_col,
                   parent=parent_col, source=source, ctx_select=ctx_select_lk,
                   join_ctx=join_ctx)
        )

    union = "\nUNION ALL ".join(
        ["SELECT * FROM level_%d" % k for k in range(0, max_depth + 1)]
    )

    return (
        "-- 自动生成，请勿手改。\n"
        "-- maxDepth=%d, source=%s\n"
        "CREATE OR REPLACE VIEW %s AS\n"
        "WITH\n%s\n%s;\n"
        % (max_depth, source, view, ",\n".join(parts), union)
    )


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--max-depth", type=int, default=9)
    p.add_argument("--view", default=None, help="视图名，默认 bom_path_<maxDepth>")
    p.add_argument("--source", default="ods.dp_mpo_mopp_bom_detial_info_dd")
    p.add_argument("--id-col", default="bom_item_id")
    p.add_argument("--node-col", default="material_code")
    p.add_argument("--parent-col", default="parent_material_code")
    p.add_argument("--context-cols", default="bom_code,bom_version",
                   help="BOM 上下文列，逗号分隔；用于把遍历限制在同一 BOM 内")
    args = p.parse_args()

    view = args.view or ("bom_path_%d" % args.max_depth)
    context_cols = [c.strip() for c in args.context_cols.split(",") if c.strip()]

    print(gen(args.max_depth, args.source, view,
              args.id_col, args.node_col, args.parent_col, context_cols))


if __name__ == "__main__":
    main()
