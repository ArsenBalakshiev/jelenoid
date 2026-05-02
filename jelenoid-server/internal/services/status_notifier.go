package services

type StatusService struct {
	activeSessions *ActiveSessionsService
}

type StatusNotifier struct {
	hub       *SSEHub
	statusSvc *StatusService
}

func NewStatusNotifier(hub *SSEHub, statusSvc *StatusService) *StatusNotifier {
	return &StatusNotifier{
		hub:       hub,
		statusSvc: statusSvc,
	}
}

func (n *StatusNotifier) OnStatusChanged() {
	status := n.statusSvc.BuildStatus()
	n.hub.Broadcast(SSEEvent{
		Event: "state-update",
		Data:  status,
	})
}