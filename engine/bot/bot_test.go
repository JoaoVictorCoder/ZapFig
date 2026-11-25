package bot

import (
	"testing"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/types"
)

func TestIsSelfChat(t *testing.T) {
	me := types.NewJID("5516999999999", types.DefaultUserServer)
	meDev := types.JID{User: me.User, Server: me.Server, Device: 12}
	lid := types.NewJID("123456789", types.HiddenUserServer)
	cli := whatsmeow.NewClient(&store.Device{ID: &meDev, LID: lid}, nil)
	other := types.NewJID("5511888888888", types.DefaultUserServer)
	cases := []struct {
		name string
		info types.MessageInfo
		want bool
	}{
		{"self pn", types.MessageInfo{MessageSource: types.MessageSource{Chat: me, IsFromMe: true}}, true},
		{"self lid", types.MessageInfo{MessageSource: types.MessageSource{Chat: lid, IsFromMe: true}}, true},
		{"eu pra outro", types.MessageInfo{MessageSource: types.MessageSource{Chat: other, IsFromMe: true}}, false},
		{"outro pra mim", types.MessageInfo{MessageSource: types.MessageSource{Chat: other, IsFromMe: false}}, false},
		{"grupo", types.MessageInfo{MessageSource: types.MessageSource{Chat: types.NewJID("1203", types.GroupServer), IsFromMe: true, IsGroup: true}}, false},
	}
	for _, c := range cases {
		if got := isSelfChat(cli, c.info); got != c.want {
			t.Errorf("%s: got %v want %v", c.name, got, c.want)
		}
	}
}

func TestOnlyDigits(t *testing.T) {
	if onlyDigits("+55 (16) 99999-9999") != "5516999999999" {
		t.Fatal("onlyDigits")
	}
}
