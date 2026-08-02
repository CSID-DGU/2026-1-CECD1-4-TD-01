from app.safety import compose_system_prompt, sanitize_response


def test_empty_response_has_safe_fallback():
    assert "다시 한번" in sanitize_response("  ")


def test_diagnostic_phrase_is_removed():
    result = sanitize_response("당신은 우울증입니다.")
    assert "우울증입니다" not in result


def test_only_one_question_is_returned():
    result = sanitize_response("오늘은 어떠세요? 식사는 하셨어요?")
    assert result == "오늘은 어떠세요. 식사는 하셨어요?"


def test_custom_prompt_keeps_safety_rules():
    result = compose_system_prompt("답변에 예시를 다섯 개 포함하세요.")
    assert "비진단 규칙" in result
    assert "예시를 다섯 개" in result


def test_tts_unfriendly_markdown_and_emoji_are_removed():
    result = sanitize_response("**괜찮습니다.** 😊\n- 천천히 말씀해 주세요.")
    assert result == "괜찮습니다. 천천히 말씀해 주세요."
