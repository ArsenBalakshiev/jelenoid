package services

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/balakshievas/jelenoid-server-go/internal/dto"
	"github.com/google/uuid"
)

type SeleniumSessionService struct {
	activeSessions   *ActiveSessionsService
	browserManager   *BrowserManagerService
	dockerService    *DockerExternalService
	sessionPublisher *SessionPublisher
	statusChan       chan struct{}

	serverAddress string
	serverPort    int
	authToken     string
	publicHost    string
	httpClient    *http.Client
	proxyClient   *http.Client
}

func NewSeleniumSessionService(
	activeSessions *ActiveSessionsService,
	browserManager *BrowserManagerService,
	dockerService *DockerExternalService,
	sessionPublisher *SessionPublisher,
	statusChan chan struct{},
	serverAddress string,
	serverPort int,
	authToken string,
	publicHost string,
) *SeleniumSessionService {
	proxyTransport := &http.Transport{
		MaxIdleConns:        2000,
		MaxIdleConnsPerHost: 500,
		MaxConnsPerHost:     500,
		IdleConnTimeout:     60 * time.Second,
		DisableKeepAlives:   true,
		DisableCompression:  true,
	}
	return &SeleniumSessionService{
		activeSessions:   activeSessions,
		browserManager:   browserManager,
		dockerService:    dockerService,
		sessionPublisher: sessionPublisher,
		statusChan:       statusChan,
		serverAddress:    serverAddress,
		serverPort:       serverPort,
		authToken:        authToken,
		publicHost:       publicHost,
		httpClient: &http.Client{
			Timeout:   300 * time.Second,
			Transport: proxyTransport,
		},
		proxyClient: &http.Client{
			Timeout:   0,
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				return http.ErrUseLastResponse
			},
			Transport: proxyTransport,
		},
	}
}

func (s *SeleniumSessionService) CreateSessionOrQueue(requestBody map[string]interface{}) (map[string]interface{}, error) {
	if err := s.authorizeSeleniumRequest(requestBody); err != nil {
		return nil, err
	}

	if s.activeSessions.TryReserveSlot() {
		result, err := s.createSessionInternal(requestBody)
		if err != nil {
			log.Println("Session creation failed, releasing previously reserved slot.")
			s.activeSessions.ReleaseSlot()
			s.ProcessQueue()
			return nil, err
		}
		return result, nil
	}

	log.Printf("No free slots. Adding request to queue. In-progress: %d/%d, Queue: %d",
		s.activeSessions.GetInProgressCount(), s.activeSessions.GetSeleniumSessionLimit(), s.activeSessions.GetQueueSize())

	future := make(chan dto.PendingRequestResult, 1)
	pendingReq := &dto.PendingRequest{
		RequestBody: requestBody,
		Future:      future,
		QueuedTime:  time.Now(),
		StartTime:   time.Now().UnixMilli(),
	}

	if s.activeSessions.OfferToQueue(pendingReq) {
		s.dispatchStatusUpdate()
		select {
		case result := <-future:
			if result.Err != nil {
				return nil, result.Err
			}
			return result.Response, nil
		case <-time.After(time.Duration(s.activeSessions.queueTimeoutMs) * time.Millisecond):
			return nil, fmt.Errorf("queue timeout")
		}
	}

	log.Println("Queue is full. Rejecting request.")
	return nil, &HTTPError{StatusCode: http.StatusServiceUnavailable, Message: "Session queue is full"}
}

func (s *SeleniumSessionService) createSessionInternal(requestBody map[string]interface{}) (map[string]interface{}, error) {
	browserInfo := s.findImageForRequest(requestBody)
	selenoidOptions := findSelenoidOptions(requestBody)
	enableVNC := getBoolOption(selenoidOptions, "enableVNC")

	if browserInfo == nil {
		return nil, &HTTPError{StatusCode: http.StatusNotFound, Message: "Not found images for your browser"}
	}

	var containerInfo *dto.ContainerInfo
	var err error
	containerInfo, err = s.dockerService.StartSeleniumContainer(browserInfo.DockerImageName, enableVNC)
	if err != nil {
		return nil, &HTTPError{StatusCode: http.StatusInternalServerError, Message: fmt.Sprintf("Failed to start container: %v", err)}
	}

	createSessionURL := fmt.Sprintf("http://%s:4444/session", containerInfo.ContainerName)
	jsonBody, _ := json.Marshal(requestBody)

	resp, err := s.httpClient.Post(createSessionURL, "application/json", bytes.NewReader(jsonBody))
	if err != nil {
		s.dockerService.StopContainer(containerInfo.ContainerID)
		return nil, &HTTPError{StatusCode: http.StatusInternalServerError, Message: fmt.Sprintf("Failed to create session in container: %v", err)}
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		s.dockerService.StopContainer(containerInfo.ContainerID)
		return nil, &HTTPError{StatusCode: http.StatusInternalServerError, Message: "Container returned non-200 status"}
	}

	bodyBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		s.dockerService.StopContainer(containerInfo.ContainerID)
		return nil, &HTTPError{StatusCode: http.StatusInternalServerError, Message: fmt.Sprintf("Failed to read response: %v", err)}
	}

	var responseBody map[string]interface{}
	if err := json.Unmarshal(bodyBytes, &responseBody); err != nil {
		s.dockerService.StopContainer(containerInfo.ContainerID)
		return nil, &HTTPError{StatusCode: http.StatusInternalServerError, Message: fmt.Sprintf("Failed to parse response: %v", err)}
	}

	responseValue, _ := responseBody["value"].(map[string]interface{})
	remoteSessionID, _ := responseValue["sessionId"].(string)

	if remoteSessionID == "" {
		s.dockerService.StopContainer(containerInfo.ContainerID)
		return nil, &HTTPError{StatusCode: http.StatusInternalServerError, Message: "Container did not return a session ID"}
	}

	hubSessionID := uuid.New().String()
	seleniumSession := &dto.SeleniumSession{
		HubSessionID:    hubSessionID,
		RemoteSessionID: remoteSessionID,
		BrowserName:     browserInfo.Name,
		Version:         browserInfo.Version,
		VNCEnabled:      enableVNC,
		ContainerInfo:   containerInfo,
	}
	s.activeSessions.SessionSuccessfullyCreated(hubSessionID, seleniumSession)
	s.dispatchStatusUpdate()

	containerCapabilities, _ := responseValue["capabilities"].(map[string]interface{})
	if containerCapabilities != nil {
		if chromeOptions, ok := containerCapabilities["goog:chromeOptions"].(map[string]interface{}); ok {
			delete(chromeOptions, "debuggerAddress")
		}

		effectiveHost := s.publicHost
		if effectiveHost == "" {
			effectiveHost = s.serverAddress
		}
		if effectiveHost == "0.0.0.0" {
			log.Println("The advertised host is '0.0.0.0'. This is likely incorrect. Please set JELENOID_PUBLIC_HOST.")
		}

		devToolsURL := fmt.Sprintf("ws://%s:%d/session/%s/se/cdp", effectiveHost, s.serverPort, hubSessionID)
		containerCapabilities["se:cdp"] = devToolsURL
	}

	responseValue["sessionId"] = hubSessionID

	sessionInfo := s.sessionPublisher.CreateSessionAndPublish("selenium", browserInfo.Version)
	seleniumSession.SessionInfo = sessionInfo

	return map[string]interface{}{"value": responseValue}, nil
}

func (s *SeleniumSessionService) DeleteSession(hubSessionID string) {
	session := s.activeSessions.SessionDeleted(hubSessionID)
	s.dispatchStatusUpdate()
	if session != nil {
		go s.dockerService.StopContainer(session.ContainerInfo.ContainerID)
		s.sessionPublisher.EndSessionByRemoteAndPublish(session.SessionInfo)
		s.ProcessQueue()
	}
}

func (s *SeleniumSessionService) ProcessQueue() {
	if s.activeSessions.GetQueueSize() > 0 {
		if s.activeSessions.TryReserveSlot() {
			nextRequest := s.activeSessions.PollFromQueue()
			s.dispatchStatusUpdate()
			if nextRequest != nil {
				log.Println("Processing next request from queue...")
				result, err := s.createSessionInternal(nextRequest.RequestBody)
				if err != nil {
					s.activeSessions.ReleaseSlot()
					nextRequest.Future <- dto.PendingRequestResult{Err: err}
					s.ProcessQueue()
				} else {
					nextRequest.Future <- dto.PendingRequestResult{Response: result}
				}
			} else {
				s.activeSessions.ReleaseSlot()
			}
		}
	}
}

func (s *SeleniumSessionService) ProxyRequest(hubSessionID string, method string, relativePath string, headers http.Header, body []byte) (*http.Response, error) {
	session := s.activeSessions.Get(hubSessionID)
	if session == nil {
		return nil, &HTTPError{StatusCode: http.StatusNotFound, Message: "Session not found: " + hubSessionID}
	}

	session.UpdateActivity()

	containerInfo := session.ContainerInfo
	pathForContainer := strings.Replace(relativePath, hubSessionID, session.RemoteSessionID, 1)

	targetURL := fmt.Sprintf("http://%s:4444%s", containerInfo.ContainerName, pathForContainer)

	var reqBody io.Reader
	if method != "GET" && len(body) > 0 {
		reqBody = bytes.NewReader(body)
	}

	req, err := http.NewRequest(method, targetURL, reqBody)
	if err != nil {
		return nil, err
	}

	for key, values := range headers {
		if !isRestrictedHeader(key) {
			for _, v := range values {
				req.Header.Add(key, v)
			}
		}
	}

	resp, err := s.proxyClient.Do(req)
	if err != nil {
		return nil, &HTTPError{StatusCode: http.StatusBadGateway, Message: fmt.Sprintf("Failed to proxy request to container: %v", err)}
	}

	return resp, nil
}

func (s *SeleniumSessionService) UploadFileToSession(hubSessionID string, fileBytes []byte) (string, error) {
	session := s.activeSessions.Get(hubSessionID)
	if session == nil {
		return "", &HTTPError{StatusCode: http.StatusNotFound, Message: "Session not found: " + hubSessionID}
	}

	return s.dockerService.CopyFileToContainer(session.ContainerInfo.ContainerID, fileBytes)
}

func (s *SeleniumSessionService) StreamLogsForSession(hubSessionID string) (<-chan []byte, error) {
	session := s.activeSessions.Get(hubSessionID)
	if session == nil {
		return nil, &HTTPError{StatusCode: http.StatusNotFound, Message: "Session not found: " + hubSessionID}
	}
	return s.dockerService.StreamContainerLogs(session.ContainerInfo.ContainerID)
}

func (s *SeleniumSessionService) dispatchStatusUpdate() {
	select {
	case s.statusChan <- struct{}{}:
	default:
	}
}

func (s *SeleniumSessionService) findImageForRequest(requestBody map[string]interface{}) *dto.BrowserInfo {
	capabilitiesRequest, _ := requestBody["capabilities"].(map[string]interface{})
	if capabilitiesRequest == nil {
		return nil
	}

	alwaysMatch, _ := capabilitiesRequest["alwaysMatch"].(map[string]interface{})
	if alwaysMatch == nil {
		alwaysMatch = make(map[string]interface{})
	}

	firstMatch, _ := capabilitiesRequest["firstMatch"].([]interface{})

	for _, fm := range firstMatch {
		firstMatchOption, ok := fm.(map[string]interface{})
		if !ok {
			continue
		}
		mergedCapabilities := make(map[string]interface{})
		for k, v := range alwaysMatch {
			mergedCapabilities[k] = v
		}
		for k, v := range firstMatchOption {
			mergedCapabilities[k] = v
		}
		browserName, _ := mergedCapabilities["browserName"].(string)
		browserVersion, _ := mergedCapabilities["browserVersion"].(string)
		if browserName != "" {
			return s.browserManager.GetBrowserInfoByBrowserNameAndVersion(browserName, browserVersion)
		}
	}

	return nil
}

func (s *SeleniumSessionService) authorizeSeleniumRequest(requestBody map[string]interface{}) error {
	if s.authToken == "" {
		return nil
	}

	capabilities, _ := requestBody["capabilities"].(map[string]interface{})
	if capabilities == nil {
		return &HTTPError{StatusCode: http.StatusUnauthorized, Message: "Invalid or missing jelenoidToken in selenoid:options"}
	}

	token := ""
	alwaysMatch, _ := capabilities["alwaysMatch"].(map[string]interface{})
	if alwaysMatch != nil {
		if options, ok := alwaysMatch["selenoid:options"].(map[string]interface{}); ok {
			if t, ok := options["jelenoidToken"].(string); ok {
				token = t
				delete(options, "jelenoidToken")
			}
		}
	}

	if token == "" {
		firstMatch, _ := capabilities["firstMatch"].([]interface{})
		for _, fm := range firstMatch {
			if match, ok := fm.(map[string]interface{}); ok {
				if options, ok := match["selenoid:options"].(map[string]interface{}); ok {
					if _, ok := options["jelenoidToken"]; ok {
						token, _ = options["jelenoidToken"].(string)
						delete(options, "jelenoidToken")
						break
					}
				}
			}
		}
	}

	if s.authToken != token {
		log.Println("Unauthorized Selenium connection attempt.")
		return &HTTPError{StatusCode: http.StatusUnauthorized, Message: "Invalid or missing jelenoidToken in selenoid:options"}
	}

	return nil
}

func findSelenoidOptions(requestBody map[string]interface{}) map[string]interface{} {
	capabilities, _ := requestBody["capabilities"].(map[string]interface{})
	if capabilities == nil {
		return make(map[string]interface{})
	}

	alwaysMatch, _ := capabilities["alwaysMatch"].(map[string]interface{})
	if alwaysMatch != nil {
		if options, ok := alwaysMatch["selenoid:options"].(map[string]interface{}); ok {
			return options
		}
	}

	firstMatch, _ := capabilities["firstMatch"].([]interface{})
	for _, fm := range firstMatch {
		if match, ok := fm.(map[string]interface{}); ok {
			if options, ok := match["selenoid:options"].(map[string]interface{}); ok {
				return options
			}
		}
	}

	return make(map[string]interface{})
}

func getBoolOption(options map[string]interface{}, key string) bool {
	val, ok := options[key].(bool)
	return ok && val
}

func isRestrictedHeader(name string) bool {
	lower := strings.ToLower(name)
	return lower == "content-length" || lower == "transfer-encoding" ||
		lower == "host" || lower == "connection" || lower == "upgrade"
}

type HTTPError struct {
	StatusCode int
	Message    string
}

func (e *HTTPError) Error() string {
	return e.Message
}

func encodeMap(m map[string]interface{}) string {
	vals := url.Values{}
	for k, v := range m {
		vals.Set(k, fmt.Sprintf("%v", v))
	}
	return vals.Encode()
}