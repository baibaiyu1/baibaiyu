import { useFullScreen } from '@react-sigma/core'
import { MaximizeIcon, MinimizeIcon } from 'lucide-react'
import { controlButtonVariant } from '@lightrag/lib/constants'
import Button from '@lightrag/components/ui/Button'
import { useTranslation } from 'react-i18next'

/**
 * Component that toggles full screen mode.
 */
const FullScreenControl = () => {
  const { isFullScreen, toggle } = useFullScreen()
  const { t } = useTranslation()

  return (
    <>
      {isFullScreen ? (
        <Button variant={controlButtonVariant} onClick={toggle} tooltip={t('graphPanel.sideBar.fullScreenControl.windowed')} size="icon">
          <MinimizeIcon />
        </Button>
      ) : (
        <Button variant={controlButtonVariant} onClick={toggle} tooltip={t('graphPanel.sideBar.fullScreenControl.fullScreen')} size="icon">
          <MaximizeIcon />
        </Button>
      )}
    </>
  )
}

export default FullScreenControl
