from dataclasses import dataclass


@dataclass(frozen=True)
class ResponseProfile:
    name: str
    max_tokens: int
    instruction: str
    max_sentences: int
    max_chars: int


CRISIS_WORDS = (
    "죽고 싶",
    "자살",
    "자해",
    "목숨",
    "사라지고 싶",
    "살고 싶지",
    "해칠 생각",
)
BRIEF_WORDS = ("짧게", "간단히", "요약", "한줄", "한 줄")
DETAILED_WORDS = ("길게", "자세히", "구체적", "상세히", "예시를", "단계별")
COMPLEX_WORDS = ("비교", "장단점", "몇 가지", "방법들", "이유와", "주의점", "계획을")
GREETINGS = ("안녕", "안녕하세요", "고마워", "고마워요", "감사합니다", "그래", "응")


def choose_response_profile(text: str) -> ResponseProfile:
    normalized = " ".join(text.lower().split())
    if any(word in normalized for word in CRISIS_WORDS):
        return ResponseProfile(
            "crisis",
            160,
            (
                "현재 안전 확인을 최우선으로 하세요. 3~4개의 짧고 분명한 문장으로 "
                "공감하고, 필요한 안전 행동을 안내한 뒤 질문은 마지막에 하나만 하세요."
            ),
            4,
            260,
        )
    if any(word in normalized for word in BRIEF_WORDS):
        return ResponseProfile(
            "brief",
            70,
            "핵심만 1~2개의 짧은 문장으로 답하세요. 질문은 필요할 때 하나만 하세요.",
            2,
            120,
        )
    if any(word in normalized for word in DETAILED_WORDS):
        return ResponseProfile(
            "detailed",
            320,
            (
                "사용자가 자세한 답변을 요청했습니다. 4~6개의 문장으로 구체적인 "
                "설명과 예시를 제공하되 같은 말을 반복하지 마세요."
            ),
            6,
            450,
        )
    if len(normalized) <= 10 and any(normalized.startswith(word) for word in GREETINGS):
        return ResponseProfile(
            "social",
            45,
            "자연스럽고 부담 없는 한 문장으로 응답하세요.",
            1,
            80,
        )
    if len(normalized) >= 180 or any(word in normalized for word in COMPLEX_WORDS):
        return ResponseProfile(
            "complex",
            220,
            "요청 조건을 반영해 3~5개의 간결한 문장으로 설명하세요.",
            5,
            360,
        )
    return ResponseProfile(
        "balanced",
        120,
        "상황에 맞는 2~3개의 짧은 문장으로 핵심만 답하세요.",
        3,
        180,
    )


def enforce_response_length(text: str, profile: ResponseProfile) -> str:
    """Bound unexpected model verbosity without cutting mid-sentence."""

    import re

    cleaned = " ".join(text.split()).strip()
    if not cleaned:
        return cleaned
    sentences = [
        match.group(0).strip()
        for match in re.finditer(r"[^.!?]+(?:[.!?]+|$)", cleaned)
        if match.group(0).strip()
    ]
    result = " ".join(sentences[: profile.max_sentences]).strip()
    if len(result) <= profile.max_chars:
        return result

    bounded = result[: profile.max_chars + 1]
    sentence_end = max(
        bounded.rfind("."),
        bounded.rfind("!"),
        bounded.rfind("?"),
    )
    if sentence_end >= max(20, profile.max_chars // 2):
        return bounded[: sentence_end + 1].strip()

    word_end = bounded.rfind(" ")
    if word_end >= max(20, profile.max_chars // 2):
        bounded = bounded[:word_end]
    else:
        bounded = bounded[: profile.max_chars]
    return bounded.rstrip(" ,;:") + "."
