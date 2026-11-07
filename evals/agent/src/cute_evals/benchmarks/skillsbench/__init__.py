"""Legacy SkillsBench adapter.

Not registered in the ``cute-evals benchmark`` CLI. Cute-Agent now uses the
project-owned ``skill-sim`` suite for deterministic local skill evaluation.
"""

from .adapter import SkillsBenchAdapter

__all__ = ["SkillsBenchAdapter"]
