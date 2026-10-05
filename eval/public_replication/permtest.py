"""Paired two-sided randomisation (sign-flip) test, percentile bootstrap CI over queries, Holm correction (plan section 6)."""
import numpy as np

N_PERM = 10_000
SEED = 42


def randomisation_test(diffs, n_perm=N_PERM, seed=SEED, block=1000):
    """p = (1 + #{permutations with |mean| >= |observed|}) / (n_perm + 1); signs drawn from default_rng(seed)."""
    d = np.asarray(diffs, dtype=np.float64)
    n = len(d)
    if n == 0:
        return float("nan"), float("nan")
    obs = abs(d.mean())
    rng = np.random.default_rng(seed)
    hits = 0
    done = 0
    while done < n_perm:
        m = min(block, n_perm - done)
        signs = rng.integers(0, 2, size=(m, n), dtype=np.int8) * 2 - 1
        means = np.abs((signs * d[None, :]).mean(axis=1))
        hits += int((means >= obs - 1e-12).sum())
        done += m
    return float(d.mean()), (1 + hits) / (n_perm + 1)


def bootstrap_ci(diffs, n_boot=10_000, seed=SEED, block=1000):
    d = np.asarray(diffs, dtype=np.float64)
    n = len(d)
    rng = np.random.default_rng(seed)
    means = []
    done = 0
    while done < n_boot:
        m = min(block, n_boot - done)
        idx = rng.integers(0, n, size=(m, n))
        means.append(d[idx].mean(axis=1))
        done += m
    means = np.concatenate(means)
    return float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5))


def holm(pvals):
    """Holm step-down adjusted p-values (same order as the input)."""
    m = len(pvals)
    order = sorted(range(m), key=lambda i: pvals[i])
    adj = [0.0] * m
    running = 0.0
    for rank, i in enumerate(order):
        running = max(running, min(1.0, (m - rank) * pvals[i]))
        adj[i] = running
    return adj
