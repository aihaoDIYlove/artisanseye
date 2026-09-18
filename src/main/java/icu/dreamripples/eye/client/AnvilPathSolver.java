package icu.dreamripples.eye.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.dries007.tfc.common.component.forge.ForgeRule;
import net.dries007.tfc.common.component.forge.ForgeStep;
import org.jetbrains.annotations.Nullable;

/**
 * 锻造最短路径求解器：在状态空间 (work, 最近三步窗口) 上做 BFS。
 * <p>
 * 设计要点（语义均对照 TFC 源码，保证与服务端判定逐字一致）：
 * <ul>
 *   <li>转移边界：TFC 不做钳制，work 越出 [0,150] 即销毁工件（AnvilBlockEntity 挂铁砧逻辑），
 *       因此越界边在搜索阶段直接剪除——给出的路径任何瞬间都不会触发销毁；</li>
 *   <li>全新工件保护：未加工过且 work==0 时服务端拒绝负向敲击（视为原地不动），等价剪除；</li>
 *   <li>目标测试：{@code rule.matches(last, secondLast, thirdLast)} 直接调用 TFC 的公开实现，
 *       加上 work 落入 [target-leeway, target+leeway]（同一 config 值）；</li>
 *   <li>窗口语义：ForgeSteps 列表按时间序存放（末位=最后一步），每按一步窗口右移一位。</li>
 * </ul>
 * 状态总数 151 × 9³ = 110,079，单次求解亚毫秒级，玩家每次敲击后重算即可。
 */
final class AnvilPathSolver
{
    private static final int WINDOW_BASE = 9 * 9; // 9 = 空 + 8 种步骤
    private static final int STATE_COUNT = (ForgeStep.LIMIT + 1) * 9 * 9 * 9;

    private static final int[] QUEUE = new int[STATE_COUNT];
    private static final int[] PARENT = new int[STATE_COUNT];

    private AnvilPathSolver() {}

    /**
     * @return 从当前状态到完成的最短步骤序列；空列表 = 已处于完成态；null = 无解。
     */
    @Nullable
    static List<ForgeStep> solve(List<ForgeRule> rules, int target, int leeway, int work, List<ForgeStep> lastSteps, boolean neverWorked)
    {
        final int last = encode(lastSteps, 0), second = encode(lastSteps, 1), third = encode(lastSteps, 2);
        final int start = state(work, last, second, third);

        if (isGoal(rules, target, leeway, work, last, second, third))
        {
            return Collections.emptyList();
        }

        Arrays.fill(PARENT, -1);
        PARENT[start] = start;
        int head = 0, tail = 0;
        QUEUE[tail++] = start;

        while (head < tail)
        {
            final int cur = QUEUE[head++];
            final int cw = cur / (WINDOW_BASE * 9);
            final int a = cur / WINDOW_BASE % 9, b = cur / 9 % 9, c = cur % 9;

            for (ForgeStep step : ForgeStep.VALUES)
            {
                // 服务端拒绝全新工件的负向首击（原地无效果，无需入队）
                if (neverWorked && cw == 0 && step.step() < 0)
                {
                    continue;
                }
                final int nw = cw + step.step();
                if (nw < 0 || nw > ForgeStep.LIMIT)
                {
                    continue; // 越界会销毁工件，此类转移永不进入路径
                }
                final int next = state(nw, step.ordinal() + 1, a, b);
                if (PARENT[next] != -1)
                {
                    continue;
                }
                PARENT[next] = cur; // 先记父指针再判目标，保证回溯链完整
                if (isGoal(rules, target, leeway, nw, next / WINDOW_BASE % 9, next / 9 % 9, next % 9))
                {
                    return reconstruct(start, next);
                }
                QUEUE[tail++] = next;
            }
        }
        return null; // 穷尽无解：诚实报告，绝不给出危险路径
    }

    private static boolean isGoal(List<ForgeRule> rules, int target, int leeway, int work, int last, int second, int third)
    {
        if (work < target - leeway || work > target + leeway)
        {
            return false;
        }
        for (ForgeRule rule : rules)
        {
            if (!rule.matches(decode(last), decode(second), decode(third)))
            {
                return false;
            }
        }
        return true;
    }

    /** 回溯路径：非起点状态窗口末位（a 槽）即最后按下的步骤 */
    private static List<ForgeStep> reconstruct(int start, int goal)
    {
        final List<ForgeStep> path = new ArrayList<>();
        for (int cur = goal; cur != start; cur = PARENT[cur])
        {
            path.add(ForgeStep.VALUES[cur / WINDOW_BASE % 9 - 1]);
        }
        Collections.reverse(path);
        return path;
    }

    /** lastSteps 按时间序（末位=最后一步）；offset 0=最后一步、1=倒数第二、2=倒数第三 */
    private static int encode(List<ForgeStep> steps, int offset)
    {
        final int index = steps.size() - 1 - offset;
        return index >= 0 ? steps.get(index).ordinal() + 1 : 0;
    }

    private static ForgeStep decode(int encoded)
    {
        return encoded == 0 ? null : ForgeStep.VALUES[encoded - 1];
    }

    private static int state(int work, int last, int second, int third)
    {
        return work * WINDOW_BASE * 9 + last * WINDOW_BASE + second * 9 + third;
    }
}
