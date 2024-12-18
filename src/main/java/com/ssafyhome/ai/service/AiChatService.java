package com.ssafyhome.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafyhome.ai.dao.AiMapper;
import com.ssafyhome.ai.dto.HouseDealPredictDto;
import com.ssafyhome.ai.dto.HouseRecommendResultDto;
import com.ssafyhome.ai.dto.PromptDealDto;
import com.ssafyhome.ai.dto.PromptResourceDto;
import com.ssafyhome.ai.util.EntityToStringUtil;
import com.ssafyhome.house.entity.HouseInfoEntity;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class AiChatService {

	private final OpenAiChatModel openAiChatModel;
	private final EntityToStringUtil entityToStringUtil;
	private final AiMapper aiMapper;
	private List<HouseInfoEntity> houseListByUser = new ArrayList<>();

	public AiChatService(
			OpenAiChatModel openAiChatModel,
			EntityToStringUtil entityToStringUtil,
			AiMapper aiMapper
	) {

		this.openAiChatModel = openAiChatModel;
		this.entityToStringUtil = entityToStringUtil;
		this.aiMapper = aiMapper;
	}

	public HouseRecommendResultDto generateResponse(PromptResourceDto promptResourceDto) {

		String command =
				"{house-list} 중에서 다음 조건에서 추출한 조건에 맞는 하나의 매물을 추천해줘, 이때 선택한 매물이 다른 매물과 비교해서 왜 선택했는지도 알려주면 좋겠어\n"
				+ "조건 : [{variables}, 문장 : {nativePrompt}] \n"
				+ "추천에 사용할 데이터 : [{house-deals}, {population}, {spot-stats}] \n"
				+ "응답은 json 형식에 따라 응답해줘 응답을 객체로 parsing 할꺼야 : {response-format}"
				;

		PromptTemplate promptTemplate = new PromptTemplate(command);

		promptTemplate.add("house-list", houseList());
		promptTemplate.add("variables", variables(promptResourceDto.getPromptVariables()));
		promptTemplate.add("nativePrompt", promptResourceDto.getNativePrompt());
		promptTemplate.add("house-deals", houseDeals());
		promptTemplate.add("population", population());
		promptTemplate.add("spot-stats", spotStats());
		promptTemplate.add("response-format", responseFormat());

		String jsonStr = openAiChatModel.call(promptTemplate.create()).getResult().getOutput().getContent();
		ObjectMapper objectMapper = new ObjectMapper();
		try {
			return objectMapper.readValue(jsonStr.substring(jsonStr.indexOf('{'), jsonStr.indexOf('}') + 1), HouseRecommendResultDto.class);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}

	}
	
	public HouseDealPredictDto generateDealResponse(PromptDealDto promptDealDto) {

		String command =
				"너는 뛰어난 부동산 분석가야. {house-list} 중에서 {houseName} 이라는 건물의 전망을 알려줘.\n"
				+ "전망 분석에 사용할 데이터 : [{house-deals}, {population}, {spot-stats}] \n"
				+ "응답은 json 형식에 따라 응답해줘. 응답을 객체로 parsing 할꺼야 : {response-format}"
				;

		PromptTemplate promptTemplate = new PromptTemplate(command);

		promptTemplate.add("house-list", houseList());
		promptTemplate.add("houseName", promptDealDto.getHouseName());
		promptTemplate.add("house-deals", houseDeals());
		promptTemplate.add("population", population());
		promptTemplate.add("spot-stats", spotStats());
		promptTemplate.add("response-format", responseDealFormat());

		String jsonStr = openAiChatModel.call(promptTemplate.create()).getResult().getOutput().getContent();
		ObjectMapper objectMapper = new ObjectMapper();
		try {
			return objectMapper.readValue(jsonStr.substring(jsonStr.indexOf('{'), jsonStr.indexOf('}') + 1), HouseDealPredictDto.class);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}

	}

	public String generateResponse(String promptText) {

		return openAiChatModel.call(promptText);
	}

	private String houseList() {

		String userSeq = SecurityContextHolder.getContext().getAuthentication().getName();
		houseListByUser = aiMapper.getHouseInfoEntityList(userSeq);

		return entityToStringUtil.convertList("즐겨찾기한 매물 정보", houseListByUser);
	}

	private String variables(List<PromptResourceDto.PromptVariable> promptVariables) {

		return entityToStringUtil.convertList("조건", promptVariables);
	}

	private String houseDeals() {

		List<String> aptList = houseListByUser.stream().map(HouseInfoEntity::getAptSeq).toList();

		return entityToStringUtil.convertList("매물 거래 정보", aiMapper.getHouseDealEntityList(aptList));
	}

	private String population() {

		List<String> sggCdList = houseListByUser.stream().map(HouseInfoEntity::getSggCd).distinct().toList();

		return entityToStringUtil.convertList("각 지역코드 별 인구통계 정보", aiMapper.getPopulationEntityList(sggCdList));
	}

	private String spotStats() {

		List<String> umdNmList = houseListByUser.stream().map(HouseInfoEntity::getUmdNm).distinct().toList();

		return entityToStringUtil.convertList("각 동 별 편의시설 갯수", aiMapper.getSpotStatEntityList(umdNmList));
	}

	private String responseFormat() {

		return "{" +
				"\"aptSeq\" : 매물 번호," +
				"\"aptName\" : 아파트 이름," +
				"\"reason\" : 왜 이 매물을 선택하게 되었는지 20자 이내로 요약해서 ~입니다 체로 대답" +
				"\"description\" : 다른 매물들과 비교했을 때 해당 매물을 선택한 이유를 조건마다 이유를 들어 분석해줘. 조건이 넘어갈 때마다 (\n) 을 붙여줘." +
				"}";
	}
	
	private String responseDealFormat() {

		return "{" +
				"\"aptSeq\" : 매물 번호," +
				"\"aptName\" : 아파트 이름," +
				"\"rate\" : 이 매물의 추천 정도를 0 ~ 10 점 사이의 정수형으로 반환" +
				"\"deal\" : 지금까지 해당 건물의 가격 증가율, 그리고 앞으로의 증가율 전망을 20자 이내로 설명해줘" +
				"\"infra\" : 주변 상권이 어떠한지, 특히 어떤 상가가 많은지 20자 이내로 설명해줘" +
				"\"population\" : 인구분포는 어떠한지, 특히 어떤 인구가 많은지 20자 이내로 설명해줘" +
				"\"metro\" : 주변 지하철이 뭐가 있는지 20자 이내로 설명해줘, " +
				"\"description\" : 전체적으로 해당 건물은 어떤 사람한테 좋을 것 같은지, 추천하는지 20자 이내로 설명해줘" +
				"}";
	}
}
