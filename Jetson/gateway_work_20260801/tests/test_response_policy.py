from app.response_policy import choose_response_profile, enforce_response_length


def test_greeting_is_short():
    profile = choose_response_profile("안녕하세요")
    assert profile.name == "social"
    assert profile.max_tokens < 300


def test_detailed_request_is_longer():
    profile = choose_response_profile("집에서 할 수 있는 활동을 길고 자세히 설명해 주세요")
    assert profile.name == "detailed"
    assert profile.max_tokens >= 300


def test_crisis_response_is_brief_and_prioritized():
    profile = choose_response_profile("지금 죽고 싶다는 생각이 들어")
    assert profile.name == "crisis"
    assert profile.max_tokens <= 320


def test_balanced_response_is_limited_to_three_sentences():
    profile = choose_response_profile("오늘 조금 피곤했어요")
    result = enforce_response_length(
        "첫 번째 문장입니다. 두 번째 문장입니다. 세 번째 문장입니다. "
        "반복되는 네 번째 문장입니다. 불필요한 다섯 번째 문장입니다.",
        profile,
    )

    assert result.count(".") == 3
    assert "네 번째" not in result
