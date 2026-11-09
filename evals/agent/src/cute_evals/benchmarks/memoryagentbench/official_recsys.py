"""Official Recsys Redial recommendation extraction and Recall@K scoring."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from rapidfuzz.distance import Levenshtein


def clean_text_elements(text: str) -> str:
    text = re.sub(r"\([^()]*\)", "", text)
    text = re.sub(r"^(?:\d+[\.\)、]?\s*[\-\—\–]?\s*)?", "", text)
    return re.sub(r"\s+", " ", text).strip()


def extract_movie_name(text: str) -> str:
    filename = text.split("/")[-1]
    cleaned = filename.replace("_", " ").replace("-", " ").replace(">", " ")
    return re.sub(r"\s+", " ", re.sub(r"\([^()]*\)", "", cleaned)).strip()


def load_entity_catalog(path: Path) -> tuple[dict[int, str], list[str]]:
    name_to_id = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(name_to_id, dict):
        raise TypeError(f"invalid Recsys entity catalog: {path}")
    id_to_name = {
        int(entity_id): extract_movie_name(str(name))
        for name, entity_id in name_to_id.items()
    }
    candidates = list(dict.fromkeys(id_to_name.values()))
    return id_to_name, candidates


def find_nearest_movie(
    target_name: str,
    candidate_movies: list[str],
) -> dict[str, Any]:
    if not candidate_movies:
        raise ValueError("Recsys entity catalog is empty")
    nearest = min(
        candidate_movies,
        key=lambda candidate: Levenshtein.distance(
            target_name.lower(),
            candidate.lower(),
        ),
    )
    return {
        "movie_name": target_name,
        "min_edit_distance": Levenshtein.distance(
            target_name.lower(),
            nearest.lower(),
        ),
        "nearest_movie": nearest,
    }


def extract_recommendation_list(
    text: str,
    candidate_movies: list[str],
) -> tuple[list[dict[str, Any]], str]:
    try:
        preference_text, recommendation_text = text.split("1.", maxsplit=1)
    except ValueError:
        preference_text = ""
        recommendation_text = text.replace(",", "\n")
    raw_recommendations = [
        clean_text_elements(item.strip())
        for item in recommendation_text.split("\n")
    ]
    return (
        [
            find_nearest_movie(item, candidate_movies)
            for item in raw_recommendations
        ],
        preference_text,
    )


def score_recsys_recommendations(
    *,
    prediction: str,
    ground_truth_ids: list[str],
    id_to_name: dict[int, str],
    candidate_movies: list[str],
) -> dict[str, Any]:
    predicted_list, _ = extract_recommendation_list(
        prediction,
        candidate_movies,
    )
    predicted_movies = [item["nearest_movie"] for item in predicted_list]
    ground_truth_movies = [
        id_to_name[int(movie_id.strip())]
        for movie_id in ground_truth_ids
    ]

    def recall_at(cutoff: int) -> float:
        if not ground_truth_movies:
            return 0.0
        return sum(
            movie in predicted_movies[:cutoff]
            for movie in ground_truth_movies
        ) / len(ground_truth_movies)

    return {
        "predictedMovies": predicted_movies,
        "groundTruthMovies": ground_truth_movies,
        "recall@1": recall_at(1),
        "recall@5": recall_at(5),
        "recall@10": recall_at(10),
    }
