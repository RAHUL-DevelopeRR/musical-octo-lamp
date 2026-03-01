--[[
  vlc_ai_subtitles.lua — VLC Lua extension
  ==========================================
  Generates offline AI subtitles for the currently playing media.

  Installation
  ------------
  Copy this file to the VLC extensions directory:
    • Linux/macOS : ~/.local/share/vlc/lua/extensions/
    • Windows     : %APPDATA%\vlc\lua\extensions\

  Then restart VLC and enable the extension via:
    View → VLC AI Subtitles

  Prerequisites
  -------------
  The vlc-ai-subtitles Python package must be installed and accessible
  via the system Python interpreter (python3):

      pip install vlc-ai-subtitles

  The extension invokes the command-line interface (CLI) as an
  external process so that all heavy computation (Whisper inference)
  runs outside VLC's process.

  License: GPL-2.0-or-later
--]]

-- ---------------------------------------------------------------------------
-- Extension metadata (required by VLC)
-- ---------------------------------------------------------------------------

function descriptor()
  return {
    title       = "VLC AI Subtitles",
    version     = "0.1.0",
    author      = "RAHUL-DevelopeRR",
    url         = "https://github.com/RAHUL-DevelopeRR/musical-octo-lamp",
    shortdesc   = "Offline AI subtitle generation using Whisper",
    description = "Transcribes the audio of the current media in real time "
               .. "using an offline Whisper model and writes an SRT subtitle file "
               .. "next to the source file.",
    capabilities = { "menu" },
  }
end

-- ---------------------------------------------------------------------------
-- State
-- ---------------------------------------------------------------------------

local dlg       = nil   -- dialog handle
local status_lbl = nil  -- status label widget

-- ---------------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------------

local function os_execute_safe(cmd)
  -- vlc.misc.mtime is not used; os.execute is the portable approach.
  local ok = os.execute(cmd)
  return ok
end

local function get_media_path()
  local item = vlc.input.item()
  if not item then return nil end
  local uri = item:uri()
  -- Strip file:// prefix for local files
  if uri:sub(1, 7) == "file://" then
    return uri:sub(8)
  end
  return uri
end

local function set_status(msg)
  if status_lbl then
    status_lbl:set_text(msg)
  end
  vlc.msg.info("[VLC AI Subtitles] " .. msg)
end

-- ---------------------------------------------------------------------------
-- Core: trigger subtitle generation
-- ---------------------------------------------------------------------------

local function generate_subtitles()
  local media_path = get_media_path()
  if not media_path then
    set_status("No media is currently loaded.")
    return
  end

  -- Derive the output SRT path (same directory and base name as media).
  local srt_path = media_path:gsub("%.[^%.]+$", "") .. ".srt"

  set_status("Starting AI transcription … (this may take a while)")

  -- Build the CLI command.  The Python package installs a 'vlc-ai-subtitles'
  -- console script; fall back to 'python3 -m vlc_ai_subtitles' if needed.
  local cmd = string.format(
    'vlc-ai-subtitles transcribe %q --output %q &',
    media_path,
    srt_path
  )

  local ok = os_execute_safe(cmd)
  if ok then
    set_status("Transcription started. Subtitle file: " .. srt_path)
  else
    set_status("Failed to start transcription. Is vlc-ai-subtitles installed?")
  end
end

local function load_subtitles()
  local media_path = get_media_path()
  if not media_path then
    set_status("No media is currently loaded.")
    return
  end

  local srt_path = media_path:gsub("%.[^%.]+$", "") .. ".srt"

  -- Check if the file exists (attempt to open it).
  local f = io.open(srt_path, "r")
  if not f then
    set_status("Subtitle file not found: " .. srt_path)
    return
  end
  f:close()

  vlc.input.add_subtitle(srt_path)
  set_status("Subtitles loaded from: " .. srt_path)
end

-- ---------------------------------------------------------------------------
-- VLC extension callbacks
-- ---------------------------------------------------------------------------

function activate()
  -- Build the dialog UI.
  dlg = vlc.dialog("VLC AI Subtitles")

  dlg:add_label("<b>Offline AI Subtitle Generator</b>", 1, 1, 2, 1)
  dlg:add_label(
    "Uses Whisper to generate subtitles for the current media.",
    1, 2, 2, 1
  )

  local gen_btn = dlg:add_button("Generate Subtitles", generate_subtitles, 1, 3, 1, 1)
  local load_btn = dlg:add_button("Load Subtitles", load_subtitles, 2, 3, 1, 1)

  status_lbl = dlg:add_label("Ready.", 1, 4, 2, 1)

  dlg:show()
end

function deactivate()
  if dlg then
    dlg:delete()
    dlg = nil
    status_lbl = nil
  end
end

function close()
  vlc.deactivate()
end

function menu()
  return { "Show" }
end

function trigger_menu(item_id)
  if item_id == 1 then
    activate()
  end
end
