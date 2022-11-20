package org.egov.inbox.service;

import static org.egov.inbox.util.FSMConstants.APPLICATIONSTATUS;
import static org.egov.inbox.util.FSMConstants.CITIZEN_FEEDBACK_PENDING_STATE;
import static org.egov.inbox.util.FSMConstants.COMPLETED_STATE;
import static org.egov.inbox.util.FSMConstants.COUNT;
import static org.egov.inbox.util.FSMConstants.DISPOSED_STATE;
import static org.egov.inbox.util.FSMConstants.DSO_INPROGRESS_STATE;
import static org.egov.inbox.util.FSMConstants.FSM_VEHICLE_TRIP_MODULE;
import static org.egov.inbox.util.FSMConstants.STATUSID;
import static org.egov.inbox.util.FSMConstants.VEHICLE_LOG;
import static org.egov.inbox.util.FSMConstants.WAITING_FOR_DISPOSAL_STATE;
import static org.egov.inbox.util.TLConstants.BUSINESS_SERVICE_PARAM;
import static org.egov.inbox.util.TLConstants.REQUESTINFO_PARAM;
import static org.egov.inbox.util.TLConstants.SEARCH_CRITERIA_PARAM;
import static org.egov.inbox.util.TLConstants.TENANT_ID_PARAM;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.commons.collections4.MapUtils;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.Role;
import org.egov.inbox.config.InboxConfiguration;
import org.egov.inbox.model.vehicle.VehicleSearchCriteria;
import org.egov.inbox.model.vehicle.VehicleTripDetail;
import org.egov.inbox.model.vehicle.VehicleTripDetailResponse;
import org.egov.inbox.model.vehicle.VehicleTripSearchCriteria;
import org.egov.inbox.repository.ElasticSearchRepository;
import org.egov.inbox.repository.ServiceRequestRepository;
import org.egov.inbox.util.ErrorConstants;
import org.egov.inbox.util.FSMConstants;
import org.egov.inbox.web.model.Inbox;
import org.egov.inbox.web.model.InboxResponse;
import org.egov.inbox.web.model.InboxSearchCriteria;
import org.egov.inbox.web.model.RequestInfoWrapper;
import org.egov.inbox.web.model.VehicleCustomResponse;
import org.egov.inbox.web.model.workflow.BusinessService;
import org.egov.inbox.web.model.workflow.ProcessInstance;
import org.egov.inbox.web.model.workflow.ProcessInstanceResponse;
import org.egov.inbox.web.model.workflow.ProcessInstanceSearchCriteria;
import org.egov.inbox.web.model.workflow.State;
import org.egov.tracer.model.CustomException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jayway.jsonpath.JsonPath;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class InboxService {

	private InboxConfiguration config;

	private ServiceRequestRepository serviceRequestRepository;

	private ObjectMapper mapper;

	private WorkflowService workflowService;

	@Autowired
	private RestTemplate restTemplate;

	@Autowired
	ElasticSearchRepository elasticSearchRepository;

	@Autowired
	public InboxService(InboxConfiguration config, ServiceRequestRepository serviceRequestRepository,
			ObjectMapper mapper, WorkflowService workflowService) {
		this.config = config;
		this.serviceRequestRepository = serviceRequestRepository;
		this.mapper = mapper;
		this.mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

		this.workflowService = workflowService;
	}

	public InboxResponse fetchInboxData(InboxSearchCriteria criteria, RequestInfo requestInfo) {

		ProcessInstanceSearchCriteria processCriteria = criteria.getProcessSearchCriteria();
		HashMap moduleSearchCriteria = criteria.getModuleSearchCriteria();
		processCriteria.setTenantId(criteria.getTenantId());

		Integer totalCount = 0;
		if (!(processCriteria.getModuleName().equals(FSMConstants.SW)
				|| processCriteria.getModuleName().equals(FSMConstants.WS)))
			totalCount = workflowService.getProcessCount(criteria.getTenantId(), requestInfo, processCriteria);
		Integer nearingSlaProcessCount = workflowService.getNearingSlaProcessCount(criteria.getTenantId(), requestInfo,
				processCriteria);
		List<String> inputStatuses = new ArrayList<>();
		if (!CollectionUtils.isEmpty(processCriteria.getStatus()))
			inputStatuses = new ArrayList<>(processCriteria.getStatus());
		StringBuilder assigneeUuid = new StringBuilder();
		String dsoId = null;
		if (requestInfo.getUserInfo().getRoles().get(0).getCode().equals(FSMConstants.FSM_DSO)) {
			Map<String, Object> searcherRequestForDSO = new HashMap<>();
			Map<String, Object> searchCriteriaForDSO = new HashMap<>();
			searchCriteriaForDSO.put(TENANT_ID_PARAM, criteria.getTenantId());
			searchCriteriaForDSO.put(FSMConstants.OWNER_ID, requestInfo.getUserInfo().getUuid());
			searcherRequestForDSO.put(REQUESTINFO_PARAM, requestInfo);
			searcherRequestForDSO.put(SEARCH_CRITERIA_PARAM, searchCriteriaForDSO);
			StringBuilder uri = new StringBuilder();
			uri.append(config.getSearcherHost()).append(config.getFsmInboxDSoIDEndpoint());

			Object resultForDsoId = restTemplate.postForObject(uri.toString(), searcherRequestForDSO, Map.class);

			dsoId = JsonPath.read(resultForDsoId, "$.vendor[0].id");

		}
		if (!ObjectUtils.isEmpty(processCriteria.getAssignee())) {
			assigneeUuid = assigneeUuid.append(processCriteria.getAssignee());
			processCriteria.setStatus(null);
		}
		// Since we want the whole status count map regardless of the status filter and
		// assignee filter being passed
		processCriteria.setAssignee(null);
		processCriteria.setStatus(null);

		List<HashMap<String, Object>> bpaCitizenStatusCountMap = new ArrayList<HashMap<String, Object>>();
		List<String> roles = requestInfo.getUserInfo().getRoles().stream().map(Role::getCode)
				.collect(Collectors.toList());

		String moduleName = processCriteria.getModuleName();
		/*
		 * SAN-920: Commenting out this code as Module name will now be passed for FSM
		 * if(ObjectUtils.isEmpty(processCriteria.getModuleName()) &&
		 * !ObjectUtils.isEmpty(processCriteria.getBusinessService()) &&
		 * (processCriteria.getBusinessService().contains("FSM") ||
		 * processCriteria.getBusinessService().contains("FSM_VEHICLE_TRIP"))){
		 * processCriteria.setModuleName(processCriteria.getBusinessService().get(0)); }
		 */
		List<HashMap<String, Object>> statusCountMap = workflowService.getProcessStatusCount(requestInfo,
				processCriteria);
		processCriteria.setModuleName(moduleName);
		processCriteria.setStatus(inputStatuses);
		processCriteria.setAssignee(assigneeUuid.toString());
		List<String> businessServiceName = processCriteria.getBusinessService();
		List<Inbox> inboxes = new ArrayList<Inbox>();
		InboxResponse response = new InboxResponse();
		JSONArray businessObjects = null;
		// Map<String,String> srvMap = (Map<String, String>)
		// config.getServiceSearchMapping().get(businessServiceName.get(0));
		Map<String, String> srvMap = fetchAppropriateServiceMap(businessServiceName, moduleName);
		if (CollectionUtils.isEmpty(businessServiceName)) {
			throw new CustomException(ErrorConstants.MODULE_SEARCH_INVLAID,
					"Bussiness Service is mandatory for module search");
		}

		Map<String, Long> businessServiceSlaMap = new HashMap<>();

		if (!CollectionUtils.isEmpty(moduleSearchCriteria)) {
			moduleSearchCriteria.put("tenantId", criteria.getTenantId());
			moduleSearchCriteria.put("offset", criteria.getOffset());
			moduleSearchCriteria.put("limit", criteria.getLimit());
			List<BusinessService> bussinessSrvs = new ArrayList<BusinessService>();
			for (String businessSrv : businessServiceName) {
				BusinessService businessService = workflowService.getBusinessService(criteria.getTenantId(),
						requestInfo, businessSrv);
				bussinessSrvs.add(businessService);
				businessServiceSlaMap.put(businessService.getBusinessService(),
						businessService.getBusinessServiceSla());
			}
			HashMap<String, String> StatusIdNameMap = workflowService.getActionableStatusesForRole(requestInfo,
					bussinessSrvs, processCriteria);
			String applicationStatusParam = srvMap.get("applsStatusParam");
			String businessIdParam = srvMap.get("businessIdProperty");
			if (StringUtils.isEmpty(applicationStatusParam)) {
				applicationStatusParam = "applicationStatus";
			}
			List<String> crtieriaStatuses = new ArrayList<String>();
			// if(!CollectionUtils.isEmpty((Collection<String>)
			// moduleSearchCriteria.get(applicationStatusParam))) {
			// //crtieriaStatuses = (List<String>)
			// moduleSearchCriteria.get(applicationStatusParam);
			// }else {
			if (StatusIdNameMap.values().size() > 0) {
				if (!CollectionUtils.isEmpty(processCriteria.getStatus())) {
					List<String> statuses = new ArrayList<String>();
					processCriteria.getStatus().forEach(status -> {
						statuses.add(StatusIdNameMap.get(status));
					});
					moduleSearchCriteria.put(applicationStatusParam,
							StringUtils.arrayToDelimitedString(statuses.toArray(), ","));
				} else {
					moduleSearchCriteria.put(applicationStatusParam,
							StringUtils.arrayToDelimitedString(StatusIdNameMap.values().toArray(), ","));
				}

			}
		}

		if (!ObjectUtils.isEmpty(processCriteria.getModuleName())
				&& processCriteria.getModuleName().equalsIgnoreCase(FSMConstants.FSM_MODULE)) {

			List<String> applicationStatus = new ArrayList<>();
			applicationStatus.add(WAITING_FOR_DISPOSAL_STATE);
			applicationStatus.add(DISPOSED_STATE);
			List<Map<String, Object>> vehicleResponse = fetchVehicleTripResponse(criteria, requestInfo,
					applicationStatus);
			BusinessService businessService = workflowService.getBusinessService(criteria.getTenantId(), requestInfo,
					FSM_VEHICLE_TRIP_MODULE);
			// log.info("businessService :::: " + businessService);
			populateStatusCountMap(statusCountMap, vehicleResponse, businessService);

			for (HashMap<String, Object> vTripMap : statusCountMap) {
				if ((WAITING_FOR_DISPOSAL_STATE.equals(vTripMap.get(APPLICATIONSTATUS))
						|| DISPOSED_STATE.equals(vTripMap.get(APPLICATIONSTATUS)))
						&& inputStatuses.contains(vTripMap.get(STATUSID))) {
					totalCount += ((int) vTripMap.get(COUNT));
				}
			}
			List<String> requiredApplications = new ArrayList<>();
			inboxes.forEach(inbox -> {
				ProcessInstance inboxProcessInstance = inbox.getProcessInstance();
				if (null != inboxProcessInstance && null != inboxProcessInstance.getState()) {
					String appStatus = inboxProcessInstance.getState().getApplicationStatus();
					if (DSO_INPROGRESS_STATE.equals(appStatus) || CITIZEN_FEEDBACK_PENDING_STATE.equals(appStatus)
							|| COMPLETED_STATE.equals(appStatus)) {
						requiredApplications.add(inboxProcessInstance.getBusinessId());
					}
				}
			});
			// log.info("requiredApplications :::: " + requiredApplications);

			List<VehicleTripDetail> vehicleTripDetail = fetchVehicleStatusForApplication(requiredApplications,
					requestInfo, criteria.getTenantId());
			// log.info("vehicleTripDetail :::: " + vehicleTripDetail);
			inboxes.forEach(inbox -> {
				if (null != inbox && null != inbox.getProcessInstance()
						&& null != inbox.getProcessInstance().getBusinessId()) {
					List<VehicleTripDetail> vehicleTripDetails = vehicleTripDetail.stream()
							.filter(trip -> inbox.getProcessInstance().getBusinessId().equals(trip.getReferenceNo()))
							.collect(Collectors.toList());
					Map<String, Object> vehicleBusinessObject = inbox.getBusinessObject();
					vehicleBusinessObject.put(VEHICLE_LOG, vehicleTripDetails);
				}
			});
			// log.info("CollectionUtils.isEmpty(inboxes) :::: " +
			// CollectionUtils.isEmpty(inboxes));
			if (CollectionUtils.isEmpty(inboxes) && totalCount > 0
					&& !moduleSearchCriteria.containsKey("applicationNos")) {
				inputStatuses = inputStatuses.stream().filter(x -> x != null).collect(Collectors.toList());
				List<String> fsmApplicationList = fetchVehicleStateMap(inputStatuses, requestInfo,
						criteria.getTenantId(), criteria.getLimit(), criteria.getOffset());
				moduleSearchCriteria.put("applicationNos", fsmApplicationList);
				moduleSearchCriteria.put("applicationStatus", requiredApplications);
//				moduleSearchCriteria.put("offset", criteria.getOffset());
//	            moduleSearchCriteria.put("limit", criteria.getLimit());
				processCriteria.setBusinessIds(fsmApplicationList);
				processCriteria.setStatus(null);
				ProcessInstanceResponse processInstanceResponse = workflowService.getProcessInstance(processCriteria,
						requestInfo);
				// log.info("processInstanceResponse :::: " + processInstanceResponse);
				List<ProcessInstance> vehicleProcessInstances = processInstanceResponse.getProcessInstances();
				Map<String, ProcessInstance> vehicleProcessInstanceMap = vehicleProcessInstances.stream()
						.collect(Collectors.toMap(ProcessInstance::getBusinessId, Function.identity()));
				JSONArray vehicleBusinessObjects = fetchModuleObjects(moduleSearchCriteria, businessServiceName,
						criteria.getTenantId(), requestInfo, srvMap);
				String businessIdParam = srvMap.get("businessIdProperty");
				// log.info("businessIdParam :::: " + businessIdParam);
				Map<String, Object> vehicleBusinessMap = StreamSupport
						.stream(vehicleBusinessObjects.spliterator(), false)
						.collect(Collectors.toMap(s1 -> ((JSONObject) s1).get(businessIdParam).toString(), s1 -> s1,
								(e1, e2) -> e1, LinkedHashMap::new));
				// log.info("businessIdParam :::: " + businessIdParam);
				// log.info("vehicleBusinessObjects.length() :::: " +
				// vehicleBusinessObjects.length());
				// log.info("vehicleProcessInstances.size() :::: " +
				// vehicleProcessInstances.size());

				if (vehicleBusinessObjects.length() > 0 && vehicleProcessInstances.size() > 0) {
					// log.info("vehicleBusinessObjects.length() :::: " +
					// vehicleBusinessObjects.length());
					// log.info("vehicleProcessInstances.size() :::: " +
					// vehicleProcessInstances.size());
					fsmApplicationList.forEach(busiessKey -> {
//						if(null != vehicleProcessInstanceMap.get(busiessKey)) {
						Inbox inbox = new Inbox();
						inbox.setProcessInstance(vehicleProcessInstanceMap.get(busiessKey));
						inbox.setBusinessObject(toMap((JSONObject) vehicleBusinessMap.get(busiessKey)));
						inboxes.add(inbox);
//						}
					});
				}
			}

			// SAN-920: Logic for aggregating the statuses of Pay now and post pay
			// application
			List<HashMap<String, Object>> aggregateStatusCountMap = new ArrayList<>();
			for (HashMap<String, Object> statusCountEntry : statusCountMap) {
				HashMap<String, Object> tempStatusMap = new HashMap<>();
				boolean matchFound = false;
				for (HashMap<String, Object> aggrMapInstance : aggregateStatusCountMap) {

					String statusMapAppStatus = (String) statusCountEntry.get("applicationstatus");
					String aggrMapAppStatus = (String) aggrMapInstance.get("applicationstatus");

					if (aggrMapAppStatus.equalsIgnoreCase(statusMapAppStatus)) {
						aggrMapInstance.put(COUNT,
								((Integer) statusCountEntry.get(COUNT) + (Integer) aggrMapInstance.get(COUNT)));
						aggrMapInstance.put(APPLICATIONSTATUS, (String) statusCountEntry.get(APPLICATIONSTATUS));
						aggrMapInstance.put(BUSINESS_SERVICE_PARAM,
								(String) statusCountEntry.get(BUSINESS_SERVICE_PARAM) + ","
										+ (String) aggrMapInstance.get(BUSINESS_SERVICE_PARAM));
						aggrMapInstance.put(STATUSID,
								(String) statusCountEntry.get(STATUSID) + "," + (String) aggrMapInstance.get(STATUSID));
						matchFound = true;
						break;
					} else {
						tempStatusMap.put(COUNT, (Integer) statusCountEntry.get(COUNT));
						tempStatusMap.put(APPLICATIONSTATUS, (String) statusCountEntry.get(APPLICATIONSTATUS));
						tempStatusMap.put(BUSINESS_SERVICE_PARAM,
								(String) statusCountEntry.get(BUSINESS_SERVICE_PARAM));
						tempStatusMap.put(STATUSID, (String) statusCountEntry.get(STATUSID));

					}
				}
				if (ObjectUtils.isEmpty(aggregateStatusCountMap)) {
					aggregateStatusCountMap.add(statusCountEntry);
				} else {
					if (!matchFound) {
						aggregateStatusCountMap.add(tempStatusMap);
					}
				}
			}

			statusCountMap = aggregateStatusCountMap;
			// log.info("removeStatusCountMap:: "+ new Gson().toJson(statusCountMap));

		}
		log.info("statusCountMap size :::: " + statusCountMap.size());

		response.setTotalCount(totalCount);
		response.setNearingSlaCount(nearingSlaProcessCount);
		response.setStatusMap(statusCountMap);
		response.setItems(inboxes);
		return response;
	}

	public List<String> fetchVehicleStateMap(List<String> inputStatuses, RequestInfo requestInfo, String tenantId,
			Integer limit, Integer offSet) {
		VehicleTripSearchCriteria vehicleTripSearchCriteria = new VehicleTripSearchCriteria();
		vehicleTripSearchCriteria.setApplicationStatus(inputStatuses);
		vehicleTripSearchCriteria.setTenantId(tenantId);
		vehicleTripSearchCriteria.setLimit(limit);
		vehicleTripSearchCriteria.setOffset(offSet);
		StringBuilder url = new StringBuilder(config.getFsmHost());
		url.append(config.getFetchApplicationIds());

		Object result = serviceRequestRepository.fetchResult(url, vehicleTripSearchCriteria);
		VehicleCustomResponse response = null;
		try {
			response = mapper.convertValue(result, VehicleCustomResponse.class);
			if (null != response && null != response.getApplicationIdList()) {
				System.out.println("size ::::  " + response.getApplicationIdList().size());
				;
				return response.getApplicationIdList();
			}
		} catch (IllegalArgumentException e) {
			throw new CustomException(ErrorConstants.PARSING_ERROR, "Failed to parse response of ProcessInstance");
		}
		return new ArrayList<>();
	}

	/**
	 * @param requiredApplications
	 * @return Description : Fetch the vehicle_trip_detail by list of reference no.
	 */
	private List<VehicleTripDetail> fetchVehicleStatusForApplication(List<String> requiredApplications,
			RequestInfo requestInfo, String tenantId) {
		VehicleTripSearchCriteria vehicleTripSearchCriteria = new VehicleTripSearchCriteria();
		vehicleTripSearchCriteria.setApplicationNos(requiredApplications);
		vehicleTripSearchCriteria.setTenantId(tenantId);
		return fetchVehicleTripDetailsByReferenceNo(vehicleTripSearchCriteria, requestInfo);
	}

	public List<VehicleTripDetail> fetchVehicleTripDetailsByReferenceNo(
			VehicleTripSearchCriteria vehicleTripSearchCriteria, RequestInfo requestInfo) {
		StringBuilder url = new StringBuilder(config.getVehicleHost());
		url.append(config.getVehicleSearchTripPath());
		Object result = serviceRequestRepository.fetchResult(url, vehicleTripSearchCriteria);
		VehicleTripDetailResponse response = null;
		try {
			response = mapper.convertValue(result, VehicleTripDetailResponse.class);
			if (null != response && null != response.getVehicleTripDetail()) {
				System.out.println("size ::::  " + response.getVehicleTripDetail().size());
				;
				return response.getVehicleTripDetail();
			}
		} catch (IllegalArgumentException e) {
			throw new CustomException(ErrorConstants.PARSING_ERROR, "Failed to parse response of ProcessInstance");
		}
		return new ArrayList<>();
	}

	private void populateStatusCountMap(List<HashMap<String, Object>> statusCountMap,
			List<Map<String, Object>> vehicleResponse, BusinessService businessService) {

		if (!CollectionUtils.isEmpty(vehicleResponse) && businessService != null) {
			List<State> appStates = businessService.getStates();

			for (State appState : appStates) {

				vehicleResponse.forEach(trip -> {

					HashMap<String, Object> vehicleTripStatusMp = new HashMap<>();
					if (trip.get(APPLICATIONSTATUS).equals(appState.getApplicationStatus())) {

						vehicleTripStatusMp.put(COUNT, trip.get(COUNT));
						vehicleTripStatusMp.put(APPLICATIONSTATUS, appState.getApplicationStatus());
						vehicleTripStatusMp.put(STATUSID, appState.getUuid());
						vehicleTripStatusMp.put(BUSINESS_SERVICE_PARAM, FSM_VEHICLE_TRIP_MODULE);
					}

					if (MapUtils.isNotEmpty(vehicleTripStatusMp))
						statusCountMap.add(vehicleTripStatusMp);
				});
			}
		}
	}

	private List<Map<String, Object>> fetchVehicleTripResponse(InboxSearchCriteria criteria, RequestInfo requestInfo,
			List<String> applicationStatus) {

		VehicleSearchCriteria vehicleTripSearchCriteria = new VehicleSearchCriteria();

		vehicleTripSearchCriteria.setApplicationStatus(applicationStatus);

		vehicleTripSearchCriteria.setTenantId(criteria.getTenantId());

		List<Map<String, Object>> vehicleResponse = null;
		VehicleCustomResponse vehicleCustomResponse = fetchApplicationCount(vehicleTripSearchCriteria, requestInfo);
		if (null != vehicleCustomResponse && null != vehicleCustomResponse.getApplicationStatusCount()) {
			vehicleResponse = vehicleCustomResponse.getApplicationStatusCount();
		} else {
			vehicleResponse = new ArrayList<Map<String, Object>>();
		}

		return vehicleResponse;
	}

	public VehicleCustomResponse fetchApplicationCount(VehicleSearchCriteria criteria, RequestInfo requestInfo) {
		StringBuilder url = new StringBuilder(config.getVehicleHost());
		url.append(config.getVehicleApplicationStatusCountPath());
		Object result = serviceRequestRepository.fetchResult(url, criteria);
		VehicleCustomResponse resposne = null;
		try {
			resposne = mapper.convertValue(result, VehicleCustomResponse.class);
		} catch (IllegalArgumentException e) {
			throw new CustomException(ErrorConstants.PARSING_ERROR, "Failed to parse response of ProcessInstance");
		}
		return resposne;
	}

	/*
	 * private String fetchUserUUID(String mobileNumber, RequestInfo requestInfo,
	 * String tenantId) { StringBuilder uri = new StringBuilder();
	 * uri.append(userHost).append(userSearchEndpoint); Map<String, Object>
	 * userSearchRequest = new HashMap<>(); userSearchRequest.put("RequestInfo",
	 * requestInfo); userSearchRequest.put("tenantId", tenantId);
	 * userSearchRequest.put("userType", "CITIZEN");
	 * userSearchRequest.put("userName", mobileNumber); String uuid = ""; try {
	 * Object user = serviceRequestRepository.fetchResult(uri, userSearchRequest);
	 * if(null != user) { uuid = JsonPath.read(user, "$.user[0].uuid"); }else {
	 * log.error("Service returned null while fetching user for username - " +
	 * mobileNumber); } }catch(Exception e) {
	 * log.error("Exception while fetching user for username - " + mobileNumber);
	 * log.error("Exception trace: ", e); } return uuid; }
	 */

	private Map<String, String> fetchAppropriateServiceMap(List<String> businessServiceName, String moduleName) {
		StringBuilder appropriateKey = new StringBuilder();
		for (String businessServiceKeys : config.getServiceSearchMapping().keySet()) {
			if (businessServiceKeys.contains(businessServiceName.get(0))) {
				appropriateKey.append(businessServiceKeys);
				break;
			}
		}
		if (ObjectUtils.isEmpty(appropriateKey)) {
			throw new CustomException("EG_INBOX_SEARCH_ERROR",
					"Inbox service is not configured for the provided business services");
		}
		// SAN-920: Added check for enabling multiple business services only for FSM
		// module
		for (String inputBusinessService : businessServiceName) {
			if (!FSMConstants.FSM_MODULE.equalsIgnoreCase(moduleName)) {
				if (!appropriateKey.toString().contains(inputBusinessService)) {
					throw new CustomException("EG_INBOX_SEARCH_ERROR", "Cross module search is NOT allowed.");
				}
			}

		}
		return config.getServiceSearchMapping().get(appropriateKey.toString());
	}

	private JSONArray fetchModuleObjects(HashMap moduleSearchCriteria, List<String> businessServiceName,
			String tenantId, RequestInfo requestInfo, Map<String, String> srvMap) {
		JSONArray resutls = null;

		if (CollectionUtils.isEmpty(srvMap) || StringUtils.isEmpty(srvMap.get("searchPath"))) {
			throw new CustomException(ErrorConstants.INVALID_MODULE_SEARCH_PATH,
					"search path not configured for the businessService : " + businessServiceName);
		}
		StringBuilder url = new StringBuilder(srvMap.get("searchPath"));
		url.append("?tenantId=").append(tenantId);

		Set<String> searchParams = moduleSearchCriteria.keySet();

		searchParams.forEach((param) -> {

			if (!param.equalsIgnoreCase("tenantId")) {

				if (moduleSearchCriteria.get(param) instanceof Collection) {
					url.append("&").append(param).append("=");
					url.append(StringUtils
							.arrayToDelimitedString(((Collection<?>) moduleSearchCriteria.get(param)).toArray(), ","));
				} else if (param.equalsIgnoreCase("appStatus")) {
					url.append("&").append("applicationStatus").append("=")
							.append(moduleSearchCriteria.get(param).toString());
				} else if (param.equalsIgnoreCase("consumerNo")) {
					url.append("&").append("connectionNumber").append("=")
							.append(moduleSearchCriteria.get(param).toString());
				} else if (null != moduleSearchCriteria.get(param)) {
					url.append("&").append(param).append("=").append(moduleSearchCriteria.get(param).toString());
				}
			}
		});

		log.info("\nfetchModuleObjects URL :::: " + url.toString());

		RequestInfoWrapper requestInfoWrapper = RequestInfoWrapper.builder().requestInfo(requestInfo).build();
		Object result = serviceRequestRepository.fetchResult(url, requestInfoWrapper);

		LinkedHashMap responseMap;
		try {
			responseMap = mapper.convertValue(result, LinkedHashMap.class);
		} catch (IllegalArgumentException e) {
			throw new CustomException(ErrorConstants.PARSING_ERROR,
					"Failed to parse response of ProcessInstance Count");
		}

		JSONObject jsonObject = new JSONObject(responseMap);

		try {
			resutls = (JSONArray) jsonObject.getJSONArray(srvMap.get("dataRoot"));
		} catch (Exception e) {
			throw new CustomException(ErrorConstants.INVALID_MODULE_DATA,
					" search api could not find data in dataroot " + srvMap.get("dataRoot"));
		}

		return resutls;
	}

	public static Map<String, Object> toMap(JSONObject object) throws JSONException {
		Map<String, Object> map = new HashMap<String, Object>();

		if (object == null) {
			return map;
		}
		Iterator<String> keysItr = object.keys();
		while (keysItr.hasNext()) {
			String key = keysItr.next();
			Object value = object.get(key);

			if (value instanceof JSONArray) {
				value = toList((JSONArray) value);
			}

			else if (value instanceof JSONObject) {
				value = toMap((JSONObject) value);
			}
			map.put(key, value);
		}
		return map;
	}

	public static List<Object> toList(JSONArray array) throws JSONException {
		List<Object> list = new ArrayList<Object>();
		for (int i = 0; i < array.length(); i++) {
			Object value = array.get(i);
			if (value instanceof JSONArray) {
				value = toList((JSONArray) value);
			}

			else if (value instanceof JSONObject) {
				value = toMap((JSONObject) value);
			}
			list.add(value);
		}
		return list;
	}

}
