package services

type queue[T any] struct {
	items []T
	head  int
}

func newQueue[T any](cap int) *queue[T] {
	return &queue[T]{items: make([]T, 0, cap)}
}

func (q *queue[T]) push(item T) {
	q.items = append(q.items, item)
}

func (q *queue[T]) pop() (T, bool) {
	if q.len() == 0 {
		var zero T
		return zero, false
	}
	item := q.items[q.head]
	var zero T
	q.items[q.head] = zero
	q.head++
	q.compact()
	return item, true
}

func (q *queue[T]) len() int {
	return len(q.items) - q.head
}

func (q *queue[T]) compact() {
	if q.head == 0 {
		return
	}
	if q.head > len(q.items)/2 {
		n := q.len()
		for i := 0; i < n; i++ {
			q.items[i] = q.items[q.head+i]
		}
		clear(q.items[n:])
		q.items = q.items[:n]
		q.head = 0
	}
}

func (q *queue[T]) removeFirst(pred func(T) bool) bool {
	n := q.len()
	for i := 0; i < n; i++ {
		if pred(q.items[q.head+i]) {
			var zero T
			idx := q.head + i
			q.items[idx] = zero
			copy(q.items[idx:], q.items[idx+1:])
			q.items = q.items[:len(q.items)-1]
			q.compact()
			return true
		}
	}
	return false
}

func (q *queue[T]) retainIf(pred func(T) bool) {
	if q.len() == 0 {
		return
	}
	writeIdx := q.head
	for i := q.head; i < len(q.items); i++ {
		if pred(q.items[i]) {
			q.items[writeIdx] = q.items[i]
			writeIdx++
		}
	}
	for i := writeIdx; i < len(q.items); i++ {
		var zero T
		q.items[i] = zero
	}
	q.items = q.items[:writeIdx]
	q.head = 0
}

func (q *queue[T]) snapshot() []T {
	n := q.len()
	if n == 0 {
		return nil
	}
	out := make([]T, n)
	for i := 0; i < n; i++ {
		out[i] = q.items[q.head+i]
	}
	return out
}

func (q *queue[T]) clear() {
	clear(q.items)
	q.items = q.items[:0]
	q.head = 0
}
